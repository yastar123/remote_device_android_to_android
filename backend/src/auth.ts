import { createHash, randomBytes } from "node:crypto";
import type { NextFunction, Request, Response } from "express";
import jwt, { type SignOptions } from "jsonwebtoken";
import bcrypt from "bcryptjs";
import { z } from "zod";
import { config } from "./config.js";
import { prisma } from "./db.js";

export const authBodySchema = z.object({
  email: z.string().trim().email().transform((value) => value.toLowerCase()),
  password: z.string().min(8).max(128),
});

export type AuthUser = {
  id: string;
  email: string;
  role: "ADMIN" | "WORKER";
};

type AccessClaims = AuthUser & {
  type: "access";
  iss: string;
};

export type AuthenticatedRequest = Request & {
  auth?: AuthUser;
};

function tokenHash(token: string) {
  return createHash("sha256").update(token).digest("hex");
}

export function hashPassword(password: string) {
  return bcrypt.hash(password, 12);
}

export function verifyPassword(password: string, passwordHash: string) {
  return bcrypt.compare(password, passwordHash);
}

export function createAccessToken(user: AuthUser) {
  const options: SignOptions = {
    expiresIn: config.ACCESS_TOKEN_TTL as SignOptions["expiresIn"],
    issuer: config.JWT_ISSUER,
  };
  return jwt.sign({ ...user, type: "access" }, config.JWT_SECRET, options);
}

export async function createRefreshToken(userId: string) {
  const token = randomBytes(48).toString("base64url");
  const expiresAt = new Date(Date.now() + config.REFRESH_TOKEN_DAYS * 86_400_000);
  await prisma.refreshToken.create({
    data: { tokenHash: tokenHash(token), userId, expiresAt },
  });
  return { token, expiresAt };
}

export async function issueTokens(user: AuthUser) {
  const accessToken = createAccessToken(user);
  const refresh = await createRefreshToken(user.id);
  return {
    accessToken,
    refreshToken: refresh.token,
    refreshTokenExpiresAt: refresh.expiresAt.toISOString(),
  };
}

export function verifyAccessToken(token: string): AuthUser {
  const claims = jwt.verify(token, config.JWT_SECRET, { issuer: config.JWT_ISSUER }) as AccessClaims;
  if (claims.type !== "access" || !claims.id) {
    throw new Error("Invalid access token");
  }
  return { id: claims.id, email: claims.email, role: claims.role };
}

function bearerToken(request: Request) {
  const value = request.header("authorization");
  if (!value?.startsWith("Bearer ")) return null;
  return value.slice("Bearer ".length).trim();
}

export function requireAuth(request: AuthenticatedRequest, response: Response, next: NextFunction) {
  const token = bearerToken(request);
  if (!token) {
    response.status(401).json({ error: "AUTH_REQUIRED", message: "Bearer access token is required." });
    return;
  }
  try {
    request.auth = verifyAccessToken(token);
    next();
  } catch {
    response.status(401).json({ error: "INVALID_TOKEN", message: "Access token is invalid or expired." });
  }
}

export function requireRole(...roles: AuthUser["role"][]) {
  return (request: AuthenticatedRequest, response: Response, next: NextFunction) => {
    if (!request.auth || !roles.includes(request.auth.role)) {
      response.status(403).json({ error: "FORBIDDEN", message: "This action is not allowed for this role." });
      return;
    }
    next();
  };
}

export async function rotateRefreshToken(refreshToken: string) {
  const current = await prisma.refreshToken.findUnique({
    where: { tokenHash: tokenHash(refreshToken) },
    include: { user: true },
  });
  if (!current || current.revokedAt || current.expiresAt <= new Date()) {
    throw new Error("Invalid refresh token");
  }

  const user: AuthUser = { id: current.user.id, email: current.user.email, role: current.user.role };
  await prisma.refreshToken.update({
    where: { id: current.id },
    data: { revokedAt: new Date() },
  });
  return issueTokens(user);
}
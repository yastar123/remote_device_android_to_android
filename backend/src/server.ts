import http from "node:http";
import { URL } from "node:url";
import express, { type NextFunction, type Request, type Response } from "express";
import cors from "cors";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import { WebSocket } from "ws";
import { z } from "zod";
import { config, corsOrigins, turnUrls } from "./config.js";
import {
  authBodySchema,
  createRefreshToken,
  hashPassword,
  issueTokens,
  requireAuth,
  requireRole,
  rotateRefreshToken,
  verifyPassword,
  type AuthenticatedRequest,
  type AuthUser,
} from "./auth.js";
import { disconnectDatabase, prisma, writeAudit } from "./db.js";
import { authenticateWebSocket, createWebSocketServer, SignalingHub } from "./ws.js";

const app = express();
const httpServer = http.createServer(app);
const hub = new SignalingHub();
const websocketServer = createWebSocketServer(hub);

app.disable("x-powered-by");
app.use(helmet());
app.use(cors({ origin: corsOrigins, credentials: true }));
app.use(express.json({ limit: "128kb" }));
app.use(
  rateLimit({
    windowMs: 60_000,
    limit: 120,
    standardHeaders: "draft-8",
    legacyHeaders: false,
  }),
);

const authLimiter = rateLimit({
  windowMs: 15 * 60_000,
  limit: 20,
  standardHeaders: "draft-8",
  legacyHeaders: false,
  message: { error: "RATE_LIMITED", message: "Too many authentication attempts. Try again later." },
});

const deviceSchema = z.object({
  deviceId: z.string().regex(/^\d{9}$/, "deviceId must contain exactly 9 digits"),
  deviceName: z.string().trim().min(1).max(120),
  androidVersion: z.string().trim().max(40).optional(),
  appVersion: z.string().trim().max(40).optional(),
});

const sessionSchema = z.object({
  receiverDeviceId: z.string().regex(/^\d{9}$/),
  controllerDeviceId: z.string().regex(/^\d{9}$/),
});

const taskSchema = z.object({
  workerDeviceId: z.string().regex(/^\d{9}$/),
  fullName: z.string().trim().min(2).max(160),
  meterId: z.string().regex(/^\d{11,12}$/),
  address: z.string().trim().min(5).max(500),
  village: z.string().trim().min(2).max(120),
  district: z.string().trim().min(2).max(120),
  city: z.string().trim().min(2).max(120),
  province: z.string().trim().min(2).max(80),
  adminId: z.string().uuid().optional(),
});

const taskStatusSchema = z.object({
  status: z.enum(["DATA_INPUT", "PLN_MOBILE", "IN_REVIEW", "COMPLETED", "NEEDS_CORRECTION"]),
});

const refreshSchema = z.object({ refreshToken: z.string().min(20) });

function publicUser(user: { id: string; email: string; role: AuthUser["role"] }) {
  return { id: user.id, email: user.email, role: user.role };
}

function asyncRoute(handler: (request: Request, response: Response) => Promise<void>) {
  return (request: Request, response: Response, next: NextFunction) => {
    void handler(request, response).catch(next);
  };
}

function requestAuth(request: Request) {
  return (request as AuthenticatedRequest).auth!;
}

let sessionExpirySchemaWarningShown = false;

function reportSessionExpiryError(error: unknown) {
  if ((error as { code?: string })?.code === "P2021") {
    if (sessionExpirySchemaWarningShown) return;
    sessionExpirySchemaWarningShown = true;
    console.warn("Session expiry sweep is waiting for Prisma migrations to create RemoteSession.");
    return;
  }
  console.error("Session expiry sweep failed", error);
}

async function expireStaleSessions() {
  const now = new Date();
  const requestCutoff = new Date(now.getTime() - config.SESSION_REQUEST_TIMEOUT_MINUTES * 60_000);
  const idleCutoff = new Date(now.getTime() - config.SESSION_IDLE_TIMEOUT_MINUTES * 60_000);
  const staleSessions = await prisma.remoteSession.findMany({
    where: {
      OR: [
        { status: "REQUESTED", requestedAt: { lt: requestCutoff } },
        { status: "APPROVED", approvedAt: { lt: idleCutoff } },
        { status: "ACTIVE", updatedAt: { lt: idleCutoff } },
      ],
    },
    select: { id: true, status: true, requesterId: true, receiverId: true },
  });

  for (const session of staleSessions) {
    const result = await prisma.remoteSession.updateMany({
      where: { id: session.id, status: session.status },
      data: { status: "EXPIRED", endedAt: now },
    });
    if (result.count === 0) continue;
    await writeAudit(null, "session.expired", "RemoteSession", session.id, {
      previousStatus: session.status,
    });
    const event = { type: "session.expired", sessionId: session.id };
    hub.emitToUser(session.requesterId, event);
    hub.emitToUser(session.receiverId, event);
  }
}

app.get(
  "/health",
  asyncRoute(async (_request, response) => {
    try {
      await prisma.$queryRaw`SELECT 1`;
      response.json({ ok: true, service: "linkdroid-backend", database: "up" });
    } catch {
      response.status(503).json({ ok: false, service: "linkdroid-backend", database: "down" });
    }
  }),
);

app.post(
  "/api/v1/auth/register",
  authLimiter,
  asyncRoute(async (request, response) => {
    const body = authBodySchema.extend({
      role: z.enum(["ADMIN", "WORKER"]).default("WORKER"),
      adminInviteCode: z.string().optional(),
    }).parse(request.body);

    if (body.role === "ADMIN" && (!config.ADMIN_INVITE_CODE || body.adminInviteCode !== config.ADMIN_INVITE_CODE)) {
      response.status(403).json({ error: "ADMIN_INVITE_REQUIRED", message: "An admin invite code is required." });
      return;
    }
    const existing = await prisma.user.findUnique({ where: { email: body.email } });
    if (existing) {
      response.status(409).json({ error: "EMAIL_IN_USE", message: "An account with this email already exists." });
      return;
    }

    const user = await prisma.user.create({
      data: {
        email: body.email,
        passwordHash: await hashPassword(body.password),
        role: body.role,
      },
    });
    await writeAudit(user.id, "user.registered", "User", user.id, { role: user.role });
    response.status(201).json({ user: publicUser(user), ...(await issueTokens(publicUser(user))) });
  }),
);

app.post(
  "/api/v1/auth/login",
  authLimiter,
  asyncRoute(async (request, response) => {
    const body = authBodySchema.parse(request.body);
    const user = await prisma.user.findUnique({ where: { email: body.email } });
    if (!user || !(await verifyPassword(body.password, user.passwordHash))) {
      response.status(401).json({ error: "INVALID_CREDENTIALS", message: "Email or password is incorrect." });
      return;
    }
    const safeUser = publicUser(user);
    response.json({ user: safeUser, ...(await issueTokens(safeUser)) });
  }),
);

app.post(
  "/api/v1/auth/refresh",
  authLimiter,
  asyncRoute(async (request, response) => {
    const { refreshToken } = refreshSchema.parse(request.body);
    try {
      response.json(await rotateRefreshToken(refreshToken));
    } catch {
      response.status(401).json({ error: "INVALID_REFRESH_TOKEN", message: "Refresh token is invalid or expired." });
    }
  }),
);

app.post(
  "/api/v1/auth/logout",
  requireAuth,
  asyncRoute(async (request, response) => {
    const user = requestAuth(request);
    await prisma.refreshToken.updateMany({
      where: { userId: user.id, revokedAt: null },
      data: { revokedAt: new Date() },
    });
    response.status(204).send();
  }),
);

app.get(
  "/api/v1/me",
  requireAuth,
  asyncRoute(async (request, response) => {
    response.json({ user: requestAuth(request) });
  }),
);

app.post(
  "/api/v1/devices/register",
  requireAuth,
  asyncRoute(async (request, response) => {
    const body = deviceSchema.parse(request.body);
    const user = requestAuth(request);
    const existing = await prisma.device.findUnique({ where: { deviceId: body.deviceId } });
    if (existing && existing.userId !== user.id) {
      response.status(409).json({ error: "DEVICE_ALREADY_PAIRED", message: "This device ID belongs to another account." });
      return;
    }
    const device = existing
      ? await prisma.device.update({
          where: { id: existing.id },
          data: {
            name: body.deviceName,
            deviceId: body.deviceId,
            androidVersion: body.androidVersion,
            appVersion: body.appVersion,
            revokedAt: null,
            lastSeenAt: new Date(),
          },
        })
      : await prisma.device.create({
          data: {
            deviceId: body.deviceId,
            name: body.deviceName,
            androidVersion: body.androidVersion,
            appVersion: body.appVersion,
            userId: user.id,
          },
        });
    await writeAudit(user.id, "device.registered", "Device", device.id, { deviceId: device.deviceId });
    response.status(existing ? 200 : 201).json({ device });
  }),
);

app.get(
  "/api/v1/devices",
  requireAuth,
  asyncRoute(async (request, response) => {
    const devices = await prisma.device.findMany({
      where: { userId: requestAuth(request).id, revokedAt: null },
      orderBy: { lastSeenAt: "desc" },
    });
    response.json({ devices });
  }),
);

app.delete(
  "/api/v1/devices/:deviceId",
  requireAuth,
  asyncRoute(async (request, response) => {
    const deviceId = String(request.params.deviceId);
    const device = await prisma.device.findFirst({
      where: { deviceId, userId: requestAuth(request).id, revokedAt: null },
    });
    if (!device) {
      response.status(404).json({ error: "DEVICE_NOT_FOUND", message: "Device was not found." });
      return;
    }
    await prisma.device.update({ where: { id: device.id }, data: { revokedAt: new Date() } });
    await writeAudit(requestAuth(request).id, "device.revoked", "Device", device.id);
    response.status(204).send();
  }),
);

app.post(
  "/api/v1/devices/:deviceId/heartbeat",
  requireAuth,
  asyncRoute(async (request, response) => {
    const deviceId = String(request.params.deviceId);
    const device = await prisma.device.findFirst({
      where: { deviceId, userId: requestAuth(request).id, revokedAt: null },
    });
    if (!device) {
      response.status(404).json({ error: "DEVICE_NOT_FOUND", message: "Device was not found." });
      return;
    }
    await prisma.device.update({
      where: { id: device.id },
      data: { lastSeenAt: new Date() },
    });
    response.status(204).send();
  }),
);

app.post(
  "/api/v1/sessions",
  requireAuth,
  requireRole("ADMIN"),
  asyncRoute(async (request, response) => {
    const body = sessionSchema.parse(request.body);
    const user = requestAuth(request);
    const [controller, receiver] = await Promise.all([
      prisma.device.findFirst({ where: { deviceId: body.controllerDeviceId, userId: user.id, revokedAt: null } }),
      prisma.device.findFirst({ where: { deviceId: body.receiverDeviceId, revokedAt: null }, include: { user: true } }),
    ]);
    if (!controller || !receiver) {
      response.status(404).json({ error: "DEVICE_NOT_FOUND", message: "Controller or receiver device was not found." });
      return;
    }
    if (receiver.userId === user.id) {
      response.status(400).json({ error: "SAME_ACCOUNT", message: "A monitoring session needs a different worker account." });
      return;
    }
    const active = await prisma.remoteSession.findFirst({
      where: {
        requesterId: user.id,
        status: { in: ["REQUESTED", "APPROVED", "ACTIVE"] },
      },
    });
    if (active) {
      response.status(409).json({ error: "ACTIVE_SESSION_EXISTS", message: "End the current session before starting another." });
      return;
    }
    const session = await prisma.remoteSession.create({
      data: {
        requesterId: user.id,
        receiverId: receiver.userId,
        controllerDeviceId: controller.id,
        receiverDeviceId: receiver.id,
      },
    });
    await writeAudit(user.id, "session.requested", "RemoteSession", session.id, { receiverDeviceId: body.receiverDeviceId });
    hub.emitToUser(receiver.userId, {
      type: "session.requested",
      sessionId: session.id,
      controllerDeviceId: controller.deviceId,
      requester: { id: user.id, email: user.email },
    });
    response.status(201).json({ session });
  }),
);

app.get(
  "/api/v1/sessions",
  requireAuth,
  asyncRoute(async (request, response) => {
    const user = requestAuth(request);
    const sessions = await prisma.remoteSession.findMany({
      where: { OR: [{ requesterId: user.id }, { receiverId: user.id }] },
      include: {
        requester: { select: { id: true, email: true } },
        receiver: { select: { id: true, email: true } },
        controllerDevice: { select: { deviceId: true, name: true } },
        receiverDevice: { select: { deviceId: true, name: true } },
      },
      orderBy: { createdAt: "desc" },
      take: 50,
    });
    response.json({ sessions });
  }),
);

async function updateSession(request: AuthenticatedRequest, response: Response, action: "approve" | "reject" | "end") {
  const user = requestAuth(request);
  const sessionId = String(request.params.id);
  const session = await prisma.remoteSession.findUnique({ where: { id: sessionId } });
  if (!session || (session.requesterId !== user.id && session.receiverId !== user.id)) {
    response.status(404).json({ error: "SESSION_NOT_FOUND", message: "Session was not found." });
    return;
  }
  if (action === "approve" && session.receiverId !== user.id) {
    response.status(403).json({ error: "ONLY_RECEIVER_CAN_APPROVE", message: "Only the worker can approve a session." });
    return;
  }
  if (action === "reject" && session.receiverId !== user.id) {
    response.status(403).json({ error: "ONLY_RECEIVER_CAN_REJECT", message: "Only the worker can reject a session." });
    return;
  }
  if (action === "approve" && session.status !== "REQUESTED") {
    response.status(409).json({ error: "INVALID_SESSION_STATE", message: "Only a requested session can be approved." });
    return;
  }
  const status = action === "approve" ? "APPROVED" : action === "reject" ? "REJECTED" : "ENDED";
  const eventName = action === "approve" ? "approved" : action === "reject" ? "rejected" : "ended";
  const updated = await prisma.remoteSession.update({
    where: { id: session.id },
    data: {
      status,
      approvedAt: action === "approve" ? new Date() : undefined,
      endedAt: action === "end" ? new Date() : undefined,
    },
  });
  await writeAudit(user.id, `session.${eventName}`, "RemoteSession", session.id);
  const targetUserId = user.id === session.requesterId ? session.receiverId : session.requesterId;
  hub.emitToUser(targetUserId, { type: `session.${eventName}`, session: updated });
  response.json({ session: updated });
}

app.post("/api/v1/sessions/:id/approve", requireAuth, asyncRoute((request, response) => updateSession(request as AuthenticatedRequest, response, "approve")));
app.post("/api/v1/sessions/:id/reject", requireAuth, asyncRoute((request, response) => updateSession(request as AuthenticatedRequest, response, "reject")));
app.post("/api/v1/sessions/:id/end", requireAuth, asyncRoute((request, response) => updateSession(request as AuthenticatedRequest, response, "end")));

app.post(
  "/api/v1/tasks",
  requireAuth,
  requireRole("WORKER"),
  asyncRoute(async (request, response) => {
    const body = taskSchema.parse(request.body);
    const user = requestAuth(request);
    const device = await prisma.device.findFirst({
      where: { deviceId: body.workerDeviceId, userId: user.id, revokedAt: null },
    });
    if (!device) {
      response.status(404).json({ error: "DEVICE_NOT_FOUND", message: "Worker device is not paired to this account." });
      return;
    }
    if (body.adminId) {
      const admin = await prisma.user.findFirst({ where: { id: body.adminId, role: "ADMIN" } });
      if (!admin) {
        response.status(400).json({ error: "ADMIN_NOT_FOUND", message: "Assigned admin was not found." });
        return;
      }
    }
    const task = await prisma.customerTask.create({
      data: {
        workerId: user.id,
        workerDeviceId: device.id,
        adminId: body.adminId,
        fullName: body.fullName,
        meterId: body.meterId,
        address: body.address,
        village: body.village,
        district: body.district,
        city: body.city,
        province: body.province,
      },
    });
    await writeAudit(user.id, "task.created", "CustomerTask", task.id);
    if (body.adminId) hub.emitToUser(body.adminId, { type: "task.created", task });
    response.status(201).json({ task });
  }),
);

app.get(
  "/api/v1/tasks",
  requireAuth,
  asyncRoute(async (request, response) => {
    const user = requestAuth(request);
    const tasks = await prisma.customerTask.findMany({
      where: user.role === "ADMIN" ? { OR: [{ adminId: user.id }, { adminId: null }] } : { workerId: user.id },
      orderBy: { updatedAt: "desc" },
      take: 100,
    });
    response.json({ tasks });
  }),
);

app.patch(
  "/api/v1/tasks/:id/status",
  requireAuth,
  asyncRoute(async (request, response) => {
    const body = taskStatusSchema.parse(request.body);
    const user = requestAuth(request);
    const taskId = String(request.params.id);
    const task = await prisma.customerTask.findUnique({ where: { id: taskId } });
    if (!task || (task.workerId !== user.id && task.adminId !== user.id)) {
      response.status(404).json({ error: "TASK_NOT_FOUND", message: "Task was not found." });
      return;
    }
    const updated = await prisma.customerTask.update({
      where: { id: task.id },
      data: {
        status: body.status,
        completedAt: body.status === "COMPLETED" ? new Date() : null,
      },
    });
    await writeAudit(user.id, "task.status_changed", "CustomerTask", task.id, { status: body.status });
    const target = user.id === task.workerId ? task.adminId : task.workerId;
    if (target) hub.emitToUser(target, { type: "task.status_changed", task: updated });
    response.json({ task: updated });
  }),
);

app.get(
  "/api/v1/turn/credentials",
  requireAuth,
  asyncRoute(async (_request, response) => {
    if (!turnUrls.length || !config.TURN_USERNAME || !config.TURN_CREDENTIAL) {
      response.status(503).json({ error: "TURN_NOT_CONFIGURED", message: "TURN credentials are not configured on the backend." });
      return;
    }
    response.json({
      iceServers: [
        { urls: ["stun:103.245.38.142:3478"] },
        { urls: turnUrls, username: config.TURN_USERNAME, credential: config.TURN_CREDENTIAL },
      ],
    });
  }),
);

app.use((error: unknown, _request: Request, response: Response, _next: NextFunction) => {
  if (error instanceof z.ZodError) {
    response.status(400).json({ error: "VALIDATION_ERROR", details: error.flatten() });
    return;
  }
  console.error(error);
  response.status(500).json({ error: "INTERNAL_ERROR", message: "The server could not complete the request." });
});

httpServer.on("upgrade", async (request, socket, head) => {
  const requestUrl = new URL(request.url ?? "/", "http://localhost");
  if (requestUrl.pathname !== "/ws") {
    socket.destroy();
    return;
  }
  try {
    const context = await authenticateWebSocket(request);
    websocketServer.handleUpgrade(request, socket, head, (websocket) => {
      websocketServer.emit("connection", websocket, request, context);
    });
  } catch {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
  }
});

httpServer.listen(config.PORT, config.HOST, () => {
  console.log(`LinkDroid backend listening on http://${config.HOST}:${config.PORT}`);
});

const sessionExpiryTimer = setInterval(() => {
  void expireStaleSessions().catch(reportSessionExpiryError);
}, 60_000);
sessionExpiryTimer.unref();
void expireStaleSessions().catch(reportSessionExpiryError);

async function shutdown(signal: string) {
  console.log(`${signal}: shutting down`);
  clearInterval(sessionExpiryTimer);
  httpServer.close();
  await disconnectDatabase();
  process.exit(0);
}

process.once("SIGINT", () => void shutdown("SIGINT"));
process.once("SIGTERM", () => void shutdown("SIGTERM"));
import "dotenv/config";
import { z } from "zod";

const envSchema = z.object({
  PORT: z.coerce.number().int().positive().default(3000),
  HOST: z.string().default("127.0.0.1"),
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(32),
  JWT_ISSUER: z.string().default("linkdroid-api"),
  ACCESS_TOKEN_TTL: z.string().default("15m"),
  REFRESH_TOKEN_DAYS: z.coerce.number().int().positive().default(30),
  CORS_ORIGIN: z.string().default("*"),
  ADMIN_INVITE_CODE: z.string().optional(),
  TURN_URLS: z.string().optional(),
  TURN_USERNAME: z.string().optional(),
  TURN_CREDENTIAL: z.string().optional(),
});

const parsed = envSchema.safeParse(process.env);
if (!parsed.success) {
  console.error("Invalid backend environment:", parsed.error.flatten().fieldErrors);
  throw new Error("Backend environment is incomplete. Copy backend/.env.example and set every required secret.");
}

export const config = parsed.data;

export const corsOrigins =
  config.CORS_ORIGIN === "*"
    ? true
    : config.CORS_ORIGIN.split(",").map((origin) => origin.trim()).filter(Boolean);

export const turnUrls = config.TURN_URLS?.split(",").map((url) => url.trim()).filter(Boolean) ?? [];
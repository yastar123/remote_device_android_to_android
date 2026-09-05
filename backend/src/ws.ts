import { WebSocket, WebSocketServer } from "ws";
import type { IncomingMessage } from "node:http";
import { URL } from "node:url";
import { z } from "zod";
import { prisma } from "./db.js";
import { verifyAccessToken, type AuthUser } from "./auth.js";

const signalSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("session.signal"),
    sessionId: z.string().uuid(),
    signalType: z.enum(["offer", "answer", "ice-candidate"]),
    payload: z.unknown(),
  }),
  z.object({
    type: z.literal("session.ping"),
    sessionId: z.string().uuid(),
  }),
  z.object({
    type: z.literal("session.command"),
    sessionId: z.string().uuid(),
    commandId: z.string().uuid(),
    command: z.discriminatedUnion("kind", [
      z.object({
        kind: z.literal("tap"),
        x: z.number().finite().min(0).max(1),
        y: z.number().finite().min(0).max(1),
        durationMs: z.number().int().min(1).max(5_000).default(80),
      }),
      z.object({
        kind: z.literal("swipe"),
        startX: z.number().finite().min(0).max(1),
        startY: z.number().finite().min(0).max(1),
        endX: z.number().finite().min(0).max(1),
        endY: z.number().finite().min(0).max(1),
        durationMs: z.number().int().min(1).max(5_000).default(400),
      }),
      z.object({
        kind: z.literal("text"),
        value: z.string().max(1_000),
      }),
      z.object({ kind: z.literal("back") }),
      z.object({ kind: z.literal("home") }),
    ]),
  }),
  z.object({
    type: z.literal("session.command.result"),
    sessionId: z.string().uuid(),
    commandId: z.string().uuid(),
    ok: z.boolean(),
    error: z.string().max(120).optional(),
  }),
]);

type Client = { socket: WebSocket; user: AuthUser; deviceId: string };

export class SignalingHub {
  private readonly clients = new Map<string, Set<Client>>();
  private readonly commandTimeouts = new Map<string, NodeJS.Timeout>();

  attach(socket: WebSocket, user: AuthUser, deviceId: string) {
    const client = { socket, user, deviceId };
    const connections = this.clients.get(user.id) ?? new Set<Client>();
    connections.add(client);
    this.clients.set(user.id, connections);

    socket.on("message", (raw) => {
      void this.handleMessage(client, raw.toString());
    });
    socket.on("close", () => this.remove(client));
    socket.on("error", () => this.remove(client));
    socket.send(JSON.stringify({ type: "connected", deviceId }));
    void this.activateApprovedSessions(user.id, deviceId);
  }

  emitToUser(userId: string, message: unknown) {
    const payload = JSON.stringify(message);
    for (const client of this.clients.get(userId) ?? []) {
      if (client.socket.readyState === WebSocket.OPEN) client.socket.send(payload);
    }
  }

  emitToDevice(userId: string, deviceId: string, message: unknown) {
    const payload = JSON.stringify(message);
    for (const client of this.clients.get(userId) ?? []) {
      if (client.deviceId === deviceId && client.socket.readyState === WebSocket.OPEN) {
        client.socket.send(payload);
      }
    }
  }

  private remove(client: Client) {
    const connections = this.clients.get(client.user.id);
    connections?.delete(client);
    if (connections?.size === 0) this.clients.delete(client.user.id);
  }

  private async handleMessage(client: Client, raw: string) {
    let message: z.infer<typeof signalSchema>;
    try {
      message = signalSchema.parse(JSON.parse(raw));
    } catch {
      client.socket.send(JSON.stringify({ type: "error", error: "INVALID_SIGNAL" }));
      return;
    }

    const session = await prisma.remoteSession.findUnique({
      where: { id: message.sessionId },
      select: {
        id: true,
        requesterId: true,
        receiverId: true,
        controllerDeviceId: true,
        receiverDeviceId: true,
        status: true,
      },
    });
    if (!session) {
      client.socket.send(JSON.stringify({ type: "error", error: "SESSION_NOT_FOUND" }));
      return;
    }
    const isController =
      session.requesterId === client.user.id && session.controllerDeviceId === client.deviceId;
    const isReceiver =
      session.receiverId === client.user.id && session.receiverDeviceId === client.deviceId;
    if (!isController && !isReceiver) {
      client.socket.send(JSON.stringify({ type: "error", error: "SESSION_FORBIDDEN" }));
      return;
    }
    if (!["APPROVED", "ACTIVE"].includes(session.status)) {
      client.socket.send(JSON.stringify({ type: "error", error: "SESSION_NOT_ACTIVE" }));
      return;
    }

    if (message.type === "session.ping") {
      await prisma.remoteSession.update({
        where: { id: session.id },
        data: { updatedAt: new Date() },
      });
      client.socket.send(JSON.stringify({ type: "session.pong", sessionId: session.id }));
      return;
    }

    if (message.type === "session.command") {
      if (!isController) {
        client.socket.send(JSON.stringify({ type: "error", error: "COMMAND_FORBIDDEN" }));
        return;
      }
      this.emitToDevice(session.receiverId, session.receiverDeviceId, {
        type: "session.command",
        sessionId: session.id,
        commandId: message.commandId,
        command: message.command,
        fromDeviceId: client.deviceId,
      });
      const previousTimeout = this.commandTimeouts.get(message.commandId);
      if (previousTimeout) clearTimeout(previousTimeout);
      this.commandTimeouts.set(
        message.commandId,
        setTimeout(() => {
          this.commandTimeouts.delete(message.commandId);
          this.emitToDevice(session.requesterId, session.controllerDeviceId, {
            type: "session.command.result",
            sessionId: session.id,
            commandId: message.commandId,
            ok: false,
            error: "COMMAND_TIMEOUT",
          });
        }, 15_000),
      );
      return;
    }

    if (message.type === "session.command.result") {
      if (!isReceiver) {
        client.socket.send(JSON.stringify({ type: "error", error: "COMMAND_RESULT_FORBIDDEN" }));
        return;
      }
      const timeout = this.commandTimeouts.get(message.commandId);
      if (timeout) clearTimeout(timeout);
      this.commandTimeouts.delete(message.commandId);
      this.emitToDevice(session.requesterId, session.controllerDeviceId, {
        type: "session.command.result",
        sessionId: session.id,
        commandId: message.commandId,
        ok: message.ok,
        ...(message.error ? { error: message.error } : {}),
        fromDeviceId: client.deviceId,
      });
      return;
    }

    const targetUserId = isController ? session.receiverId : session.requesterId;
    this.emitToUser(targetUserId, {
      type: "session.signal",
      sessionId: session.id,
      fromDeviceId: client.deviceId,
      signalType: message.signalType,
      payload: message.payload,
    });
  }

  private async activateApprovedSessions(userId: string, deviceId: string) {
    const sessions = await prisma.remoteSession.findMany({
      where: {
        status: "APPROVED",
        OR: [
          { requesterId: userId, controllerDevice: { deviceId } },
          { receiverId: userId, receiverDevice: { deviceId } },
        ],
      },
      select: { id: true, requesterId: true, receiverId: true },
    });

    for (const session of sessions) {
      const updated = await prisma.remoteSession.update({
        where: { id: session.id },
        data: { status: "ACTIVE" },
      });
      this.emitToUser(session.requesterId, { type: "session.active", session: updated });
      this.emitToUser(session.receiverId, { type: "session.active", session: updated });
    }
  }
}

export function createWebSocketServer(hub: SignalingHub) {
  const server = new WebSocketServer({ noServer: true });
  server.on("connection", (socket: WebSocket, request: IncomingMessage, context: { user: AuthUser; deviceId: string }) => {
    hub.attach(socket, context.user, context.deviceId);
  });
  return server;
}

export async function authenticateWebSocket(request: IncomingMessage) {
  const requestUrl = new URL(request.url ?? "/", "http://localhost");
  const token = requestUrl.searchParams.get("access_token");
  const deviceId = requestUrl.searchParams.get("device_id");
  if (!token || !deviceId) throw new Error("WebSocket access_token and device_id are required");
  const user = verifyAccessToken(token);
  const device = await prisma.device.findFirst({
    where: { deviceId, userId: user.id, revokedAt: null },
    select: { id: true },
  });
  if (!device) throw new Error("Device is not registered for this user");
  await prisma.device.update({
    where: { id: device.id },
    data: { lastSeenAt: new Date() },
  });
  return { user, deviceId };
}
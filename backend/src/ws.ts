import { WebSocket, WebSocketServer } from "ws";
import type { IncomingMessage } from "node:http";
import { URL } from "node:url";
import { z } from "zod";
import { prisma } from "./db.js";
import { verifyAccessToken, type AuthUser } from "./auth.js";

const signalSchema = z.object({
  type: z.enum(["session.signal", "session.ping"]),
  sessionId: z.string().uuid(),
  signalType: z.enum(["offer", "answer", "ice-candidate"]),
  payload: z.unknown(),
});

type Client = { socket: WebSocket; user: AuthUser; deviceId: string };

export class SignalingHub {
  private readonly clients = new Map<string, Set<Client>>();

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
  }

  emitToUser(userId: string, message: unknown) {
    const payload = JSON.stringify(message);
    for (const client of this.clients.get(userId) ?? []) {
      if (client.socket.readyState === WebSocket.OPEN) client.socket.send(payload);
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

    const targetUserId = isController ? session.receiverId : session.requesterId;
    this.emitToUser(targetUserId, {
      type: "session.signal",
      sessionId: session.id,
      fromDeviceId: client.deviceId,
      signalType: message.signalType,
      payload: message.payload,
    });
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
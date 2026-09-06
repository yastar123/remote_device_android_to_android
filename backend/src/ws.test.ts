import test from "node:test";
import assert from "node:assert/strict";

import { prisma } from "./db.js";
import { SignalingHub } from "./ws.js";

function makeSocket() {
  return {
    readyState: 1,
    on: () => undefined,
    send: () => true,
    close: () => undefined,
  };
}

test("session.signal is relayed to the actual receiver device id", async () => {
  const remoteSession = prisma.remoteSession as any;
  const originalFindUnique = remoteSession.findUnique;
  const originalFindMany = remoteSession.findMany;
  const sessionId = "22222222-2222-4222-8222-222222222222";
  const requesterId = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa";
  const receiverId = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb";
  const controllerDeviceId = "555000001";
  const receiverDeviceId = "555000002";

  remoteSession.findUnique = async () =>
    ({
      id: sessionId,
      requesterId,
      receiverId,
      controllerDevice: { deviceId: controllerDeviceId },
      receiverDevice: { deviceId: receiverDeviceId },
      status: "APPROVED",
    }) as any;
  remoteSession.findMany = async () => [];

  const hub = new SignalingHub();
  const receiverPayloads: string[] = [];

  const controllerSocket = makeSocket();
  const receiverSocket = {
    ...makeSocket(),
    send: (payload: string) => {
      receiverPayloads.push(payload);
      return true;
    },
  };

  hub.attach(
    controllerSocket as any,
    { id: requesterId, email: "admin@example.com", role: "ADMIN" } as any,
    controllerDeviceId,
  );
  hub.attach(
    receiverSocket as any,
    { id: receiverId, email: "worker@example.com", role: "WORKER" } as any,
    receiverDeviceId,
  );

  const controllerClient = [...(hub as any).clients.get(requesterId)!][0];

  try {
    await (hub as any).handleMessage(
      controllerClient,
      JSON.stringify({
        type: "session.signal",
        sessionId,
        signalType: "offer",
        payload: { sdp: "v=0\r\n" },
      }),
    );

    const signalPayloads = receiverPayloads.filter((payload) =>
      payload.includes('"type":"session.signal"'),
    );
    assert.equal(
      signalPayloads.length,
      1,
      "receiver should receive the signal",
    );
    const message = JSON.parse(signalPayloads[0]);
    assert.equal(message.type, "session.signal");
    assert.equal(message.fromDeviceId, controllerDeviceId);
    assert.equal(message.signalType, "offer");
  } finally {
    remoteSession.findUnique = originalFindUnique;
    remoteSession.findMany = originalFindMany;
  }
});

test("session.signal rejects an unowned device", async () => {
  const remoteSession = prisma.remoteSession as any;
  const originalFindUnique = remoteSession.findUnique;
  const originalFindMany = remoteSession.findMany;
  const sessionId = "33333333-3333-4333-8333-333333333333";
  const requesterId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const receiverId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

  remoteSession.findUnique = async () => ({
    id: sessionId,
    requesterId,
    receiverId,
    controllerDevice: { deviceId: "555000003" },
    receiverDevice: { deviceId: "555000004" },
    status: "ACTIVE",
  });
  remoteSession.findMany = async () => [];

  try {
    const hub = new SignalingHub();
    const unauthorizedSocket = makeSocket();
    const errors: string[] = [];
    (unauthorizedSocket as any).send = (payload: string) => {
      errors.push(payload);
      return true;
    };
    hub.attach(
      unauthorizedSocket as any,
      { id: requesterId, email: "admin@example.com", role: "ADMIN" } as any,
      "555999999",
    );
    const client = [...(hub as any).clients.get(requesterId)!][0];

    await (hub as any).handleMessage(
      client,
      JSON.stringify({
        type: "session.signal",
        sessionId,
        signalType: "offer",
        payload: { sdp: "v=0\r\n" },
      }),
    );

    assert.ok(errors.some((payload) => payload.includes("SESSION_FORBIDDEN")));
  } finally {
    remoteSession.findUnique = originalFindUnique;
    remoteSession.findMany = originalFindMany;
  }
});

import { PrismaClient } from "@prisma/client";

export const prisma = new PrismaClient({
  log: process.env.NODE_ENV === "development" ? ["warn", "error"] : ["error"],
});

export async function writeAudit(
  actorId: string | null,
  action: string,
  entityType: string,
  entityId: string,
  metadata?: unknown,
) {
  await prisma.auditLog.create({
    data: {
      actorId,
      action,
      entityType,
      entityId,
      metadata: metadata === undefined ? undefined : JSON.parse(JSON.stringify(metadata)),
    },
  });
}

export async function disconnectDatabase() {
  await prisma.$disconnect();
}
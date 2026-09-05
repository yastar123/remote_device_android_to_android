---
name: Prisma CLI compatibility
description: Prisma version constraint for LinkDroid backend tooling
---

LinkDroid backend uses Prisma 6.x stable for the normal `generate`, `format`, and
`migrate deploy` workflow. The newer Prisma 8 release candidate available in the
workspace used a different CLI surface and did not register those commands.

**Why:** The backend cannot generate `PrismaClient` or prepare migrations when the
release-candidate CLI is installed, even though the schema itself is valid.

**How to apply:** Keep `prisma` and `@prisma/client` on the same stable major
version unless the migration workflow is deliberately re-evaluated.
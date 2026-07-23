# Document Store Service

Persistence service for generated CV and cover-letter text, version metadata,
and exported DOCX/PDF bytes.

This migration baseline is **not beta-ready**. The current API does not
authenticate callers or enforce document ownership, uses an in-memory H2
database with an exposed console, and lacks production retention, encryption,
migrations, concurrency controls, and bounded content rules. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven
- Spring Data JPA
- H2 for the current development baseline only

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot.

## Build

```bash
mvn -B clean verify
```

The clean migration snapshot passed 14 tests. Passing tests do not resolve the
document ownership and production-storage blockers.

## Safe local use

Use synthetic data only. Runtime databases, exported files, real documents,
and personal data must never be committed.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).

# Document Store Service

Persistence service for generated CV and cover-letter text, version metadata,
and exported DOCX/PDF bytes.

This service is **not beta-ready**. Its producer-side API authenticates callers
and enforces document/file ownership, while its runtime storage boundary now
fails closed unless durable PostgreSQL, verified TLS, managed credentials,
managed encryption/key references, encrypted backups and reviewed Flyway
migrations are configured. Approved consumers and Infrastructure have not
completed identity, secret/key and deployment rollout, and production
retention, binary-storage separation, concurrency controls and bounded content
rules remain open. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

The bounded generated-document cleanup dependency from Job Finder, and the
separation from normal search aggregation, are defined in the Infrastructure
[Job Search architecture ADR](https://github.com/jobseekercopilot/infrastructure/blob/develop/docs/adr/0001-job-search-architecture-and-ownership.md).

The source-backed document journey, systems of record, trust boundaries,
functional readiness classification and beta test matrix are defined in
[`docs/adr/0001-document-architecture-and-ownership.md`](docs/adr/0001-document-architecture-and-ownership.md).
The ADR is the approved implementation boundary, not a beta-readiness claim;
its linked security, persistence, lifecycle, export, client and E2E issues
remain required.

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven
- Spring Data JPA
- PostgreSQL 15+ for runtime persistence
- Flyway for reviewed schema migrations
- H2 in PostgreSQL compatibility mode for isolated tests only

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the executable OpenAPI
1.1.0 contract. Maven verification fails when the running contract drifts from
this file.

The identity sources, least-privilege service roles, authorization matrix,
stable denial rules and deployment dependencies are defined in
[`docs/AUTHORIZATION_BOUNDARY.md`](docs/AUTHORIZATION_BOUNDARY.md).

## Build

```bash
mvn -B clean verify
```

Docker is required because the verification gate starts isolated PostgreSQL
containers for production-schema and recovery evidence.

The suite includes real JWKS access-token validation, service-role isolation,
owner-scoped repository queries, foreign/missing UUID equivalence,
environment-data isolation, contract drift checks and a real PostgreSQL
migration/application-restart/backup/restore/deletion drill with checksum
evidence. Passing it does not resolve consumer rollout, deployed platform
evidence, binary storage separation or document lifecycle blockers.

To intentionally refresh the contract after reviewing an API change:

```bash
mvn -B -Dtest=OpenApiExportTest -DdocumentStore.updateContract=true test
```

## Safe local use

Use synthetic data only. Runtime databases, exported files, real documents,
and personal data must never be committed.

Production storage settings, migration/rollback rules, encrypted
backup/restore evidence and the local-test boundary are documented in
[`docs/STORAGE_OPERATIONS.md`](docs/STORAGE_OPERATIONS.md).

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).

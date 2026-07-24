# Document Store Service

Persistence service for generated CV and cover-letter text, version metadata,
and exported DOCX/PDF bytes.

This service is **not beta-ready**. Its producer-side API now authenticates
callers and enforces document/file ownership, but the approved consumers and
Infrastructure have not completed identity rollout. It also uses an in-memory
H2 database with an exposed console and lacks production retention, encryption,
migrations, concurrency controls, and bounded content rules. See
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
- H2 for the current development baseline only

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

The STORE-01 suite includes real JWKS access-token validation, service-role
isolation, owner-scoped repository queries, foreign/missing UUID equivalence,
environment-data isolation and contract drift checks. Passing it does not
resolve the consumer rollout or production-storage blockers.

To intentionally refresh the contract after reviewing an API change:

```bash
mvn -B -Dtest=OpenApiExportTest -DdocumentStore.updateContract=true test
```

## Safe local use

Use synthetic data only. Runtime databases, exported files, real documents,
and personal data must never be committed.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).

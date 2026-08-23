# Document Store Service

## Role in Job Seeker Copilot

| Role | Called by | Calls | Data | Local port |
|---|---|---|---|---:|
| System of record for document families, immutable versions, files, lineage and retention lifecycle | Document Generation/BFF, Export, Tracker and Reporting | Application Tracker workflows; ClamAV | PostgreSQL metadata + configured object store | 8089 |

See the central [document journey](https://docs.jobseekercopilot.com/journeys/documents/), [domain model](https://docs.jobseekercopilot.com/data/domain-models/), and [data ownership](https://docs.jobseekercopilot.com/data/ownership/).

Persistence service for generated CV and cover-letter text, relational file
metadata, and private object-backed DOCX/PDF bytes.

This service is implemented and exercised for controlled private-beta document
families, versions, downloads and upload scanning. Its producer-side API
authenticates callers and enforces document/file ownership, while its production storage boundary
fails closed unless durable PostgreSQL, verified TLS, managed credentials,
managed encryption/key references, encrypted backups and reviewed Flyway
migrations are configured. Version families now have transaction-scoped
locking, database uniqueness, retry keys and explicit restoration. Production
file writes additionally require a
private S3-compatible bucket and managed SSE-KMS key. Local Compose uses
persistent PostgreSQL, filesystem object bytes and ClamAV; production still
requires the managed identity, secret/key, bucket and backup rollout described
below. DOC-09 policy is approved and cross-service tombstone/hash-scrubbing
guards now exist in the repository, but the client recovery journey,
production backup/object/log expiry, administrator deployment, integrated
consumer idempotency and deployed reconciliation alerting remain open. See
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
- S3-compatible private object storage for DOCX/PDF bytes
- Flyway for reviewed schema migrations
- H2 in PostgreSQL compatibility mode for isolated tests only

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the executable OpenAPI
4.4.0 contract. It includes content-free paged family summaries, newest-first
server-numbered history, safe exact-artifact manifests and a concurrency- and
idempotency-protected explicit current-pointer command. Approval and current
selection are independent. Exact retained artifacts can be downloaded through
their document-version/artifact relationship even when inactive or archived;
successful downloads never activate or otherwise mutate them. The contract
also retains immutable
profile/evidence-snapshot provenance, validated claim-ledger identity,
grounding state, parent-version linkage and the producer-only owner-scoped
atomic cleanup command
used by Application Tracker's durable generated-withdrawal workflow. Replaying
the same exact document set is safe, and a failure rolls back the entire Store
transaction. Maven verification fails when the running contract drifts from
this file.

Version 3.3.0 also adds a no-store account export. Coordinated account deletion
uses a separate internal lifecycle-token route to recoverably delete owned
documents while retaining legal-held records; irreversible production purge
remains governed by the independent DOC-09 deployment gate.

Version 3.4.0 adds a reader-authorized, owner-scoped, content-free document
activity feed for version creation, successful download, current-version
changes, archive and restore. Command replays and state-preserving no-ops do not
create duplicate activity, and the activity rows follow the approved 365-day
lifecycle-audit retention policy.

Version 4.0.0 adds producer-only, application-scoped PDF/DOCX uploads. Each
operation durably moves through quarantine, mandatory ClamAV scanning,
bounded deterministic extraction and atomic publication. Only a clean
`READY` operation exposes an approved immutable version and exact original
artifact. Original-byte and normalised-text SHA-256 values remain distinct;
uploads do not call Payment, CV/Letter Service, OpenAI or another public
scanner.

Version 4.1.0 adds an internal, environment-data-authenticated runtime-owner
reset and verification boundary. It accepts only the deterministic synthetic
owner derived from the named scenario and identity and is enabled only for an
explicitly isolated non-production database. Existing public and fixture
operations are unchanged.

Version 4.2.0 adds a retention-administrator-only permanent account-document
erasure contract. It snapshots an exact owner/document scope, removes every S3
object version before transactionally deleting live owner data, resumes safely
after failure, and reports backup-retention-pending truth until a bounded
backup window and explicit operator evidence have both been satisfied. Its
stable write fence and retained exact scopes make restore replay fail closed.
Production is disabled until the separate Infrastructure capability is pinned.
See
[`docs/ACCOUNT_PERMANENT_ERASURE.md`](docs/ACCOUNT_PERMANENT_ERASURE.md).

Version 4.3.0 makes the erasure flow recoverable after an older database
restore without weakening its delete ordering. Before any object or database
erase, the service durably enters `JOURNAL_PENDING`, conditionally writes and
verifies a canonical recovery record in a separate immutable SSE-KMS S3
bucket, and binds the exact object version and digest in PostgreSQL. A
retention-administrator-only restore-replay command consumes that record,
re-erases only its exact scopes and starts a fresh backup-retention horizon.
The owner-fingerprint write fence supports a bounded primary/previous key ring;
removing a key while its verifier remains retained fails closed.

Version 4.4.0 keeps the v2 readiness fields and adds an explicit overdue
aggregate so a valid 35-day recovery-copy window is visible without blocking
release readiness.

The identity sources, least-privilege service roles, authorization matrix,
stable denial rules and deployment dependencies are defined in
[`docs/AUTHORIZATION_BOUNDARY.md`](docs/AUTHORIZATION_BOUNDARY.md).
The private-beta DOCX/PDF allow-list, byte/archive limits, generated download
names, quarantine behavior and no-paid-scanner decision are defined in
[`docs/FILE_VALIDATION.md`](docs/FILE_VALIDATION.md).
The database invariants, PostgreSQL lock boundary, `Idempotency-Key` contract,
conflict behavior and version restoration API are defined in
[`docs/VERSION_CONCURRENCY.md`](docs/VERSION_CONCURRENCY.md).
The low-cardinality metric catalogue, redaction rules, dependency-aware health
groups, alert thresholds, cost-free synthetic path and privacy-safe incident/
recovery runbook are defined in
[`docs/OBSERVABILITY_AND_OPERATIONS.md`](docs/OBSERVABILITY_AND_OPERATIONS.md).
The repository does not provision paid monitoring infrastructure.
The durable storage-operation journal, bounded reconciler, privacy-safe
metrics and guarded orphan-recovery procedure are defined in
[`docs/STORAGE_RECONCILIATION.md`](docs/STORAGE_RECONCILIATION.md).
Approval/version behavior is defined in
[`docs/DOCUMENT_LIFECYCLE.md`](docs/DOCUMENT_LIFECYCLE.md). Recoverable
deletion, legal hold, fail-closed purge, approved retention periods and
production approval gates are defined in
[`docs/RETENTION_AND_PURGE.md`](docs/RETENTION_AND_PURGE.md).

## Build

```bash
mvn -B clean verify
```

Docker is required because the verification gate starts isolated PostgreSQL
containers for production-schema and recovery evidence.

The suite includes real JWKS access-token validation, service-role isolation,
owner-scoped repository queries, foreign/missing UUID equivalence,
environment-data isolation, contract drift checks and a real PostgreSQL
migration/application-restart/backup/restore/deletion drill, legacy BYTEA
upgrade, object restart recovery, SSE-KMS request and checksum quarantine
evidence, parallel version allocation, idempotent retries, transactional
rollback and restore, malicious/corrupt file rejection, safe-download controls,
byte-for-byte inactive and archived artifact downloads, relationship and
owner isolation, owner-scoped archive/restore/soft deletion, guarded purge, legal hold and
bounded audit maintenance. It also covers bounded operation metrics,
log/metric redaction, correlation propagation, database/object-storage
readiness and reconciliation signals.
Passing it does not resolve consumer rollout, deployed platform evidence,
deployed monitoring/reconciliation alerting or production purge enablement.

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

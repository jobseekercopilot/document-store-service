# Beta-readiness audit

Audit date: 2026-07-23

STORE-01 producer update: 2026-07-24

STORE-02 repository storage update: 2026-07-26

DOC-03 object-storage separation update: 2026-07-26

DOC-05 file-validation update: 2026-07-26

DOC-10 repository observability update: 2026-07-26

DOC-08 storage-reconciliation update: 2026-07-26

DOC-09 retention-lifecycle update: 2026-07-26

Status: **Not ready for private beta**

## STORE-01 producer boundary

The service now validates platform RS256/JWKS access tokens and distinct
runtime producer, reader and environment-data credentials. Every public
document/file operation resolves one owner and uses owner-scoped document or
file queries. Foreign and missing UUIDs return the same stable denial, and
application logs use redacted route families rather than raw owner, application,
document or file IDs.

This resolves the unauthenticated producer implementation finding only.
Gateway, CV/Cover Letter, Document Export and Infrastructure rollout plus
integrated cross-user journeys remain beta dependencies. See
[`AUTHORIZATION_BOUNDARY.md`](AUTHORIZATION_BOUNDARY.md).

## Cross-repository ownership decision

The source-backed current flow, target systems of record, trust boundaries,
functional classification, dependency order and executable test matrix are
published in
[`docs/adr/0001-document-architecture-and-ownership.md`](adr/0001-document-architecture-and-ownership.md).
That decision resolves DOC-01's architecture boundary but does not close any
linked implementation blocker or make this service beta-ready.

## STORE-02 durable storage foundation

The runtime no longer has an H2 fallback. It requires PostgreSQL, verified
database TLS, injected credentials, managed database/backup encryption
declarations and non-secret key references. Flyway owns schema changes,
Hibernate is validate-only, Flyway clean is disabled, and H2 plus its console
are absent from runtime.

The repository test gate migrates a real PostgreSQL 15 container, persists
synthetic text and bytes, verifies constraint and credential failures, repeats
the application startup/migration path against the durable database, performs a
custom-format backup/restore, validates byte checksum integrity and proves
ordered synthetic deletion. The operational contract is published in
[`STORAGE_OPERATIONS.md`](STORAGE_OPERATIONS.md).

This is repository evidence, not deployed managed-service evidence. INFRA-08
still owns secret/key injection, rotation and encryption proof.

## DOC-03 object-storage separation

PostgreSQL now owns file metadata only. New DOCX/PDF writes go through a
provider-neutral object interface; the production S3-compatible adapter
requests managed SSE-KMS encryption and does not set a public ACL. Metadata
records the owner, generated-document link, safe name/type, object key,
version, byte size, SHA-256, lifecycle status and timestamps.

Flyway V2 preserves existing V1 BYTEA rows as `LEGACY_DATABASE`. The startup
migrator writes and verifies each object before clearing its database BLOB.
Downloads verify size and SHA-256 and quarantine mismatches. File/document
deletion commits `DELETE_PENDING` before object and metadata removal. Isolated
tests use a unique temporary filesystem root; production startup rejects it.

This closes the repository implementation portion of DOC-03. INFRA-08 still
owns deployed private-bucket, credentials, KMS, backup/versioning and recovery
evidence. DOC-08 still owns scheduled orphan/missing-object reconciliation.

## DOC-05 file-validation boundary

The private-beta allow-list is now explicit: approved producers may store
validated generated DOCX/PDF, while browser replacement uploads are DOCX-only.
Decoded and multipart byte limits are aligned; DOCX entry count, individual and
total expansion and compression ratio are bounded. Inspection rejects
traversal, duplicates, corrupt XML/archives, macros, password-protected
packages, embedded/ActiveX content, unsafe external relationships and imported
content. Credential-free HTTPS hyperlink relationships are the sole external
DOCX relationship exception for generated and stored-byte validation; upload
validation still rejects every external relationship. Generated and
stored-byte PDF validation has the corresponding bounded exception for a
standard page `Link` annotation with one credential-free, host-bearing HTTPS
`URI` action. PDFBox structural inspection rejects encryption, JavaScript,
forms, embedded files, additional/automatic/action-chain behaviour and every
other annotation or action. Application-upload validation rejects all PDF
annotations and external/active constructs, while browser replacement remains
DOCX-only.

Caller names and MIME values are treated as untrusted. Only canonical MIME
values and `document-<file UUID>.<extension>` names are stored and returned.
Downloads include attachment, `nosniff`, private/no-store caching and length
headers. Validation completes before persistence or active-version changes;
stored objects that later fail checksum or content revalidation are marked
`UNAVAILABLE`.

The supported matrix, limits, rejection outcomes and explicit no-paid-scanner
decision are published in [`FILE_VALIDATION.md`](FILE_VALIDATION.md). This is
bounded static inspection, not complete malware detection. EXPORT-02 retains
renderer budgets, fonts/licences and generated-document quality.

Repository verification passed `mvn -B --no-transfer-progress -Ddebug=false
clean verify`: 61 tests, zero failures, zero errors and zero skipped. The
source-only Dockerfile also built successfully. This is repository evidence,
not a production deployment or image-vulnerability scan.

## DOC-10 repository observability boundary

The service now records low-cardinality operation success/failure, duration
and payload-size metrics for generated documents and object-backed exported
files. Authentication and authorization denials use redacted route families;
multiple-active repairs and object-compensation failures emit reconciliation
signals. Arbitrary metric dimensions collapse instead of creating
identifier-labelled series.

Liveness is process-only. Readiness requires application readiness, PostgreSQL
health and a redacted read-path check through `DocumentObjectStorage`. The
vendor-neutral alert catalogue, cost-free synthetic path and privacy-safe
incident/recovery runbook are published in
[`OBSERVABILITY_AND_OPERATIONS.md`](OBSERVABILITY_AND_OPERATIONS.md).

This is repository evidence only. No paid monitoring resource was created.
DOC-08 must integrate durable reconciliation, while DOCGEN-19 and
Infrastructure must prove cross-service dashboards, alerts and deployed
recovery drills.

## DOC-08 storage-reconciliation boundary

File writes now reserve a stable file ID, version and opaque object key in a
durable PostgreSQL journal before calling object storage. File metadata and the
journal's `COMMITTED` transition share one transaction; an interrupted write
therefore remains a recoverable `PREPARED` operation rather than an
unidentifiable object.

The scheduled reconciler completes `DELETE_PENDING` cleanup, rolls back
uncommitted prepared objects, repairs prepared journals whose metadata did
commit, quarantines missing/corrupt/unsafe available objects, and detects
unknown orphan binaries without deleting them. Each category is bounded and
uses a durable rotating cursor so a persistent failure cannot starve later
records. A transaction-scoped lock serializes workers. Logs and metrics expose
fixed aggregate outcomes only.

The state model, AWS prefix/IAM requirement and guarded manual procedure are in
[`STORAGE_RECONCILIATION.md`](STORAGE_RECONCILIATION.md). Repository evidence
does not prove deployed ECS task-role binding, S3/KMS controls, CloudWatch
alerts or paired managed-store recovery. Those remain Infrastructure
dependencies.

Local verification passed `mvn -B --no-transfer-progress -Ddebug=false clean
verify`: 105 tests, zero failures, zero errors and zero skipped, including real
PostgreSQL 15 migration/schema/concurrency/recovery evidence. The packaged
image built locally and reached healthy twice across a graceful container
restart against one disposable PostgreSQL database; startup reconciliation
completed on both runs. All synthetic containers and the isolated network were
removed. DOC-08 item/run metrics now use DOC-10's existing bounded
`document.store.reconciliation.count` contract rather than a second metric
family. This is repository and local-container evidence, not a deployed AWS
control.

## DOC-09 retention-lifecycle boundary

Document approval (`DRAFT`/`APPROVED`) is now separate from retention
(`AVAILABLE`/`ARCHIVED`/`DELETED`/`PURGED`). Archive, restore and normal deletion are
owner-scoped, family-locked and retry-safe. Archive and deletion deselect the
version; restore never silently selects it. Soft deletion retains text,
metadata and binaries for recovery while blocking file access, mutation,
approval, current selection and new application references.

Irreversible purge requires a distinct retention-administrator identity, an
explicit approved-policy version and enable flag, an expired recovery window,
no legal hold, an authoritative Tracker association snapshot and projection,
and no unresolved `PREPARED` storage operation. It removes Store text and
objects while retaining a scrubbed owner-visible tombstone, exact content-free
application/freeze associations and bounded audit event. Bounded maintenance can remove only expired
completed journal rows and lifecycle events; it is disabled and fail-closed
without an approved policy and never deletes `PREPARED` rows or reconciliation
cursors.

The approved periods, exact user copy, support/legal-hold process, backup and
export boundaries, and production approval checklist are published in
[`RETENTION_AND_PURGE.md`](RETENTION_AND_PURGE.md). This repository slice does
record product/legal/privacy approval dated 2026-08-07. Client recovery UX,
managed backup/object/log expiry, administrator credential deployment and
integrated cross-user evidence remain beta dependencies; production purge
therefore remains off.

Local verification passed `mvn -B --no-transfer-progress -Ddebug=false clean
verify`: 111 tests, zero failures, zero errors and zero skipped. It includes the
V6 lifecycle schema on real PostgreSQL 15, cross-owner transition denial,
repeat-safe archive/restore/delete/purge, protected application history, legal
hold, retained recovery bytes, `PREPARED` journal protection and bounded
completed-audit cleanup. This is repository evidence, not production policy or
AWS deployment evidence. The source Dockerfile also built successfully, and
the resulting image migrated a disposable PostgreSQL 15 database through V6
and returned `{"status":"UP"}`. The synthetic containers and network were
removed afterward.

## Verified responsibility

The service stores generated CV/cover-letter text in `GeneratedDocument`, file
metadata in `ExportedDocumentFile`, and DOCX/PDF bytes through
`DocumentObjectStorage`. Generated-document versions can be activated or
deactivated, while exported file replacements mark older files inactive.

## Migration evidence

- Source was copied from the untracked service directory in the intact root
  workspace; no standalone source history was available.
- `target/`, generated binaries, logs, databases, exported documents,
  recordings, and environment files are excluded.
- The migration-time contract is `contracts/openapi.json`.
- Gitleaks and targeted personal-data checks passed on the source snapshot.
- `mvn -B clean verify` passed from the clean snapshot: 14 tests, zero
  failures, zero errors, zero skipped.
- The current gate includes real PostgreSQL Flyway/JPA mapping evidence,
  synthetic database recovery, V1 BYTEA upgrade, isolated object restart,
  managed S3 encryption request, checksum quarantine and object cleanup.
- The current source-only candidate container builds locally. It was not
  deployed or vulnerability-scanned.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 61 dependencies, 9 vulnerable dependencies, 137
  vulnerability matches, including 17 Critical and 37 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Confirmed blockers

1. Approved consumers and Infrastructure have not yet rolled out the Document
   Store bearer/service identity and owner-context contract end to end.
2. Integrated cross-user tests do not yet cover the complete
   Gateway/CV/Export/Store/Application Tracker journey.
3. The repository now separates relational metadata and object bytes and
   includes bounded scheduled reconciliation, but the deployed private bucket,
   ECS task-role credentials, KMS key, alerts, object backup/versioning and
   restore evidence remain owned by Infrastructure.
4. Draft/approved and retention state, generation provenance, recovery
   deadlines, lifecycle audit and legal-hold/purge guards now exist in this
   repository. Product/legal policy approval, client copy, cross-service
   application-link protection, model/parser claim evidence and platform
   backup/log/export expiry remain open.
5. Document and exported-file version allocation is now serialized by
   PostgreSQL transaction-scoped locks and protected by database uniqueness.
   Stable operation keys replay the original result or return a deterministic
   conflict when reused with a different request.
6. Activation/deactivation and file restoration now run as one constrained
   transaction with an enforced single-current marker. Upstream producers
   still need to roll out stable keys under DOCGEN-09.
7. The deprecated application deactivation compatibility path remains a no-op;
   Application Tracker reference lifecycle still needs integrated
   reconciliation under APP-08.
8. Application request/service logs and metric labels redact stable owner and
   resource identifiers, while lifecycle events record content-free
   transitions. Operation/denial/reconciliation metrics and
   database/object-storage readiness exist in the repository, but deployed
   dashboards, alerts, log retention and audit-access policy remain
   incomplete.
9. Tests now cover authentication, service least privilege, owner and
    cross-user denial, happy-path CRUD/replacement, production configuration,
    PostgreSQL migration/application-restart persistence, backup/restore
    integrity, legacy object migration, checksum quarantine, E2E seed/reset and
    synthetic object deletion, malicious/corrupt content, spoofed metadata,
    archive limits, safe downloads, concurrent versioning, retry
    replay/conflict, rollback/restore, retention transitions, legal hold,
    guarded purge, completed-journal retention and storage reconciliation
    against PostgreSQL. They do not cover product/legal policy approval,
    deployed encryption/backup expiry or integrated consumers.
10. Current Spring, Tomcat, Jackson, logging, PostgreSQL driver and Swagger UI
    dependency findings include untriaged Critical/High advisories. The local
    image baseline remains owned by DOC-11; this slice does not upgrade or
    waive those dependencies.

## Required validation

Before beta, evidence must show authenticated ownership on every query and
mutation, durable encrypted storage with migrations and backup/restore,
explicit draft/final/version states, constrained concurrent writes, retention
and deletion controls, robust file inspection, redacted auditability, and
negative security tests.

The current passing build is only a baseline; it is not readiness evidence.

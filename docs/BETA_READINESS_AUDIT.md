# Beta-readiness audit

Audit date: 2026-07-23

STORE-01 producer update: 2026-07-24

STORE-02 repository storage update: 2026-07-26

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
still owns secret/key injection, rotation and encryption proof. DOC-03 still
owns relational metadata/object-storage separation and binary lifecycle.

## Verified responsibility

The service stores generated CV/cover-letter text and metadata in
`GeneratedDocument` and stores DOCX/PDF bytes in `ExportedDocumentFile`.
Generated-document versions can be activated or deactivated, while exported
file replacements mark older files inactive.

## Migration evidence

- Source was copied from the untracked service directory in the intact root
  workspace; no standalone source history was available.
- `target/`, generated binaries, logs, databases, exported documents,
  recordings, and environment files are excluded.
- The migration-time contract is `contracts/openapi.json`.
- Gitleaks and targeted personal-data checks passed on the source snapshot.
- `mvn -B clean verify` passed from the clean snapshot: 14 tests, zero
  failures, zero errors, zero skipped.
- The current STORE-02 repository gate passes 35 tests with zero failures,
  errors or skips. It includes real PostgreSQL Flyway/JPA mapping evidence and
  the synthetic recovery drill.
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
3. Generated CV/cover-letter text and exported bytes remain co-located in
   PostgreSQL until DOC-03 establishes the approved encrypted object-storage
   lifecycle; deployed encryption/key evidence remains owned by INFRA-08.
4. There is no draft/final/approved lifecycle, prompt version, model version,
   generation schema version, claim evidence, retention deadline, deletion
   audit, or legal-hold/export state.
5. `nextVersion` reads then increments without locking or a uniqueness
   constraint; concurrent writes can allocate duplicate versions.
6. Activation/deactivation updates are not protected as one transactional,
   constrained operation, so multiple active versions can exist.
7. Base64 document-file requests and stored document fields lack bounded
   content/field limits. Multipart limits alone do not protect internal calls.
8. DOCX inspection only looks for two ZIP entries and does not bound entry
    count, compression ratio, total expanded bytes, macros, external
    relationships, or active content. PDF signatures are not validated.
9. File metadata still trusts caller filenames and MIME types. File ownership
    is now joined to the owning document for public UUID lookups.
10. Deactivation scans all documents in memory for an application.
11. Application request/service logs now redact stable owner and resource
    identifiers, but audit-event coverage and log-retention policy remain
    incomplete.
12. Tests now cover authentication, service least privilege, owner and
    cross-user denial, happy-path CRUD/replacement, production configuration,
    PostgreSQL migration/application-restart persistence, backup/restore
    integrity and synthetic storage deletion. They do not yet cover concurrent
    versioning, governed lifecycle deletion/retention, deployed encryption,
    malicious uploads, large payloads or integrated consumers.
13. Current Spring, Tomcat, Jackson, logging, and Swagger UI dependency
    findings include untriaged Critical/High advisories; the container has not
    been scanned.

## Required validation

Before beta, evidence must show authenticated ownership on every query and
mutation, durable encrypted storage with migrations and backup/restore,
explicit draft/final/version states, constrained concurrent writes, retention
and deletion controls, robust file inspection, redacted auditability, and
negative security tests.

The current passing build is only a baseline; it is not readiness evidence.

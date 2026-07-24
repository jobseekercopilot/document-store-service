# Beta-readiness audit

Audit date: 2026-07-23

STORE-01 producer update: 2026-07-24

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
- The candidate container built locally, but the image was not deployed or
  vulnerability-scanned.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 61 dependencies, 9 vulnerable dependencies, 137
  vulnerability matches, including 17 Critical and 37 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Confirmed blockers

1. Approved consumers and Infrastructure have not yet rolled out the Document
   Store bearer/service identity and owner-context contract end to end.
2. Integrated cross-user tests do not yet cover the complete
   Gateway/CV/Export/Store/Application Tracker journey.
3. Generated CV/cover-letter text and exported bytes are stored as plaintext
   LOBs without an approved encryption, key-management, or storage boundary.
4. The default database is in-memory H2; data disappears on restart.
5. H2 Console is enabled and reachable from other hosts, SQL is logged, and
   Hibernate `ddl-auto: update` replaces controlled migrations.
6. There is no draft/final/approved lifecycle, prompt version, model version,
   schema version, claim evidence, retention deadline, deletion audit, or
   legal-hold/export state.
7. `nextVersion` reads then increments without locking or a uniqueness
   constraint; concurrent writes can allocate duplicate versions.
8. Activation/deactivation updates are not protected as one transactional,
   constrained operation, so multiple active versions can exist.
9. Base64 document-file requests and stored document fields lack bounded
   content/field limits. Multipart limits alone do not protect internal calls.
10. DOCX inspection only looks for two ZIP entries and does not bound entry
    count, compression ratio, total expanded bytes, macros, external
    relationships, or active content. PDF signatures are not validated.
11. File metadata still trusts caller filenames and MIME types. File ownership
    is now joined to the owning document for public UUID lookups.
12. Deactivation scans all documents in memory for an application.
13. Application request/service logs now redact stable owner and resource
    identifiers, but audit-event coverage and log-retention policy remain
    incomplete.
14. Tests now cover authentication, service least privilege, owner and
    cross-user denial plus happy-path CRUD/replacement. They do not yet cover
    concurrent versioning, retention/deletion, encryption, malicious uploads,
    large payloads, durable restart or integrated consumers.
15. Current Spring, Tomcat, Jackson, logging, and Swagger UI dependency
    findings include untriaged Critical/High advisories; the container has not
    been scanned.

## Required validation

Before beta, evidence must show authenticated ownership on every query and
mutation, durable encrypted storage with migrations and backup/restore,
explicit draft/final/version states, constrained concurrent writes, retention
and deletion controls, robust file inspection, redacted auditability, and
negative security tests.

The current passing build is only a baseline; it is not readiness evidence.

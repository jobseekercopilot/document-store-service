# Beta-readiness audit

Audit date: 2026-07-23

Status: **Not ready for private beta**

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

1. Controllers have no authentication or authorisation. Callers can create,
   list, read, delete, activate, deactivate, upload, and download by raw
   user/application/document/file identifiers.
2. User identity is accepted in request bodies and path parameters rather than
   derived from a trusted authenticated principal.
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
11. File metadata trusts caller filenames and MIME types; ownership is not
    joined when fetching a file by UUID.
12. Deactivation scans all documents in memory for an application.
13. Observability logs stable personal-data identifiers and has no documented
    redaction/retention policy or security audit events.
14. Tests prove happy-path CRUD and basic replacement but do not cover
    cross-user denial, concurrent versioning, retention/deletion, encryption,
    malicious uploads, large payloads, or durable restart.
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

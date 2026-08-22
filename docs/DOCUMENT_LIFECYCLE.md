# Document lifecycle and immutable application references

Document Store owns document content, lifecycle and family versions.
Application Tracker owns the exact document-version references used by an
application. Document approval and retention are separate state machines.

## Approval and version lifecycle

- A create request always produces a `DRAFT`. A draft is never current and
  cannot be referenced by an application.
- `PATCH /api/v1/documents/{id}/approve` records explicit approval only. It
  never changes the family's current pointer.
- A regenerated or replacement document supplies `documentFamilyId`. Document
  Store allocates the next version under an owner-scoped family lock.
- An authenticated owner explicitly moves current with
  `PATCH /api/v1/documents/families/{familyId}/current`, an expected-pointer
  assertion and a required `Idempotency-Key`. The command may select an older
  approved, available version and never creates or copies a version.
- `GET /api/v1/documents/{id}/reference` returns an owner-scoped descriptor only
  for an approved and `AVAILABLE` version. It excludes document content.
- Application Tracker copies the canonical descriptor into its
  application-used fields. Later changes to `current` do not change that frozen
  historical reference.

`active` remains in the API as a deprecated alias for `current` while existing
consumers migrate. Create requests cannot set it to true.

`GET /api/v1/documents/families` pages content-free family summaries.
`GET /api/v1/documents/families/{familyId}` returns trusted newest-first
versions and exact safe artifact manifests. Neither endpoint exposes document
content, original filenames, content digests, evidence payloads or scanner
details.

## Retention lifecycle

| State | Meaning | Allowed next states | File behavior |
| --- | --- | --- | --- |
| `AVAILABLE` | The version is retained and may be used according to its approval state | `ARCHIVED`, `DELETED` | Read and mutation allowed |
| `ARCHIVED` | Recoverable and not current; retained outside the normal active journey | `AVAILABLE`, `DELETED` | Existing files remain readable; mutation and application reference are blocked |
| `DELETED` | Soft deleted and recoverable until the configured deadline | `AVAILABLE`, eventual purge | Text and files remain stored; file access and mutation are blocked |
| `PURGED` | Minimal owner-visible version tombstone; no content, file, filename, complete hash or evidence payload remains | None | Irreversible |

Archive, restore and soft delete are owner-scoped and idempotent:

- `PATCH /api/v1/documents/{id}/archive` archives and deselects a version.
- `PATCH /api/v1/documents/{id}/restore` restores either archived or deleted
  content to `AVAILABLE`, without automatically making it current.
- `DELETE /api/v1/documents/{id}` is a recoverable soft delete. It sets
  `purgeEligibleAt` from the configured recovery window and keeps text,
  metadata and binaries intact.
- `GET /api/v1/documents/{id}/lifecycle-events` returns the owner-scoped
  transition history while the document exists.

Application Tracker uses the producer-only
`POST /api/v1/documents/application-withdrawals` command for generated-only
withdrawal. The command accepts one stable operation ID, application ID and at
most two exact document IDs. It locks the operation, verifies every document
belongs to the bound owner and soft-deletes the complete set in one database
transaction. If one document is absent, foreign, held or otherwise ineligible,
none of the documents change. An exact replay returns success without a second
lifecycle event; reuse of the operation ID with a changed payload returns
`409`.

An archived or deleted version cannot be approved, selected as current,
referenced for a new application, or receive a new exported file. Application
history is not rewritten when a version is archived or soft deleted. Every
transition is projected to Application Tracker before Store mutation. If
association or projection evidence is unavailable, the operation fails and the
local document remains unchanged. A purged projection retains exact
family/version identity without requiring a complete content hash.

Irreversible purge is a distinct retention-administrator operation. It is
disabled by default and stays disabled unless both an explicit enable flag and
an approved policy version are configured. See
[`RETENTION_AND_PURGE.md`](RETENTION_AND_PURGE.md).

## Generation provenance

Generated drafts may carry the non-PII generation release, resource versions
and SHA-256 hashes established by DOCGEN-05. The prompt, source profile/job
payload and generated content are not duplicated into provenance. A generated
draft without complete provenance can be previewed but cannot be approved.

Uploaded documents do not accept AI generation provenance.

## Migration rule

Legacy records predate explicit approval. Migration V4 assigns each one its own
family and marks it as a non-current draft. It does not infer approval from the
old `active` flag. Migration V6 marks existing documents `AVAILABLE`; it does
not infer archive, deletion or legal-hold state. A user or approved
orchestration flow must explicitly approve or transition an eligible version.

The remaining production rollout is owned by Infrastructure. Generated
withdrawal cleanup is atomic and replay-safe; authoritative association lookup
does not rely on Store's legacy `applicationId`. Production purge remains
disabled until backup, object-version and log expiry plus the administrator
deployment process are evidenced.

# Document lifecycle and immutable application references

Document Store owns document content, lifecycle and family versions. Application
Tracker owns the exact document-version references used by an application.

## Beta lifecycle

- A create request always produces a `DRAFT`. A draft is never current and
  cannot be referenced by an application.
- `PATCH /api/v1/documents/{id}/approve` records explicit approval and selects
  that immutable version as the current version in its family.
- A regenerated or replacement document supplies `documentFamilyId`. Document
  Store allocates the next version under an owner-scoped family lock.
- Approval of a new version changes `current`; it does not mutate or delete an
  older version.
- `GET /api/v1/documents/{id}/reference` returns an owner-scoped descriptor only
  for an approved version. It deliberately excludes document content.
- Application Tracker copies the canonical descriptor into its
  application-used fields. Later changes to `current` do not change that frozen
  historical reference.

`active` remains in the API as a deprecated alias for `current` while existing
consumers migrate. Create requests cannot set it to true.

## Generation provenance

Generated drafts may carry the non-PII generation release, resource versions and
SHA-256 hashes established by DOCGEN-05. The prompt, source profile/job payload
and generated content are not duplicated into provenance. A generated draft
without complete provenance can be previewed but cannot be approved.

Uploaded documents do not accept AI generation provenance.

## Migration rule

Legacy records predate explicit approval. Migration V4 assigns each one its own
family and marks it as a non-current draft. It does not infer approval from the
old `active` flag. A user or approved orchestration flow must explicitly approve
an eligible version.

Archive, retention, purge protection and application-link reconciliation remain
owned by DOC-09, DOC-08 and APP-08.

# Version concurrency and retry contract

This document defines the Document Store transaction boundary delivered by
STORE-03. It covers generated-document versions and exported DOCX/PDF file
versions. The wider lifecycle and immutable Application Tracker reference
model remain owned by DOC-06.

## Database invariants

Flyway migration `V3__enforce_atomic_document_versions.sql` makes the database
the final authority for these rules:

| Aggregate | Unique version | At most one current version |
| --- | --- | --- |
| Generated document | `(user_id, application_id, document_type, version)` | `(user_id, application_id, document_type, current_slot)` |
| Exported file | `(generated_document_id, file_type, version)` | `(generated_document_id, file_type, current_slot)` |

`current_slot` is `1` when `active=true` and `NULL` otherwise. Check
constraints prevent the marker and public state from disagreeing. SQL unique
indexes then permit retained inactive versions while rejecting two current
versions. Operation keys are also unique per owner and operation kind, and a
key can exist only with a 64-character request fingerprint.

Generated documents without an `application_id` are not a version family.
They retain the compatibility behavior of a standalone version. DOC-06 owns
the decision to require an application or another canonical aggregate ID for
every approved document.

Migration V3 deliberately fails if existing data already contains duplicate
versions or multiple current rows. Operators must preserve a backup, inspect
the conflicting records and apply a reviewed data-repair migration. The
migration must not guess which historical document is authoritative.

## Transaction and locking boundary

Every create, activate, deactivate, restore and version-aware delete runs in a
database transaction.

- PostgreSQL takes a transaction-scoped advisory lock derived from a
  SHA-256-scoped aggregate key. It is automatically released on commit or
  rollback and works across service replicas.
- H2 uses a process-local transaction lock only for isolated tests. H2 is not
  an approved runtime database.
- Version allocation happens after the aggregate lock is held.
- The prior current row is flushed inactive before the new or restored row is
  flushed current. Both changes still belong to one transaction, so a later
  failure restores the prior database state.
- Unique indexes and check constraints remain the last line of defence if an
  unsupported writer bypasses the service lock.

The generated-document `version` request field is retained for contract
compatibility. For an application-linked document the server allocates the
next version. A supplied value must equal that allocation or the request
returns `409 Conflict`.

## Idempotency-Key

The following mutation APIs accept the optional `Idempotency-Key` header:

- `POST /api/v1/documents`
- `POST /api/v1/document-files`
- `POST /api/v1/documents/{generatedDocumentId}/files/upload`

Keys are opaque, case-sensitive, 1-128 characters, and limited to letters,
digits, `.`, `_`, `:`, and `-`. A caller must generate one stable key for one
logical operation and reuse it only when retrying that operation.

For a valid retry:

1. the service locks the owner/key pair;
2. it compares a canonical SHA-256 request fingerprint;
3. it returns the original document or file record without allocating a new
   version or changing the current version.

Reusing a key with a different canonical request returns `409 Conflict`.
Untrusted filename and MIME claims are validated on every file request, but
the file fingerprint uses the canonical owner, document ID, file type, source
and content checksum. Keys and fingerprints are stored but are not logged.

The header remains optional so existing approved consumers do not break during
the rollout. Calls without it are concurrency-safe but a repeated call is a
new operation. DOCGEN-09 and export/client integration must send a stable key
before they claim retry idempotency.

## Restore and retention

Generated document versions are restored with the existing application/type
activation endpoint. Retained exported files are restored with:

`PATCH /api/v1/document-files/{id}/active`

Restore is owner-scoped, accepts only an `AVAILABLE` stored object, and
atomically switches the current version for the same document/file type. It
does not change the bytes or version number.

STORE-03 does not define how long an inactive version is retained or whether
an application-used version may be deleted. DOC-09 owns retention, archive,
legal-hold, recovery and purge policy. DOC-06 owns immutable application-used
references.

## Failure and retry behavior

- Validation completes before locks mutate current state.
- A database failure rolls back version allocation and current selection.
- A database failure observed while storing file metadata triggers immediate
  best-effort deletion of the newly written object.
- An object write failure occurs before current metadata changes.
- A same-key/same-request retry returns the original result.
- A same-key/different-request or stale explicit version returns a stable
  conflict and must not be retried with that key.

Cross-store database/object failures after an unexpected commit boundary,
orphan discovery and scheduled repair remain the explicit scope of DOC-08.
This service logs a compensation failure but does not make a destructive
reconciliation guess.

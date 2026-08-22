# Document retention, recovery and purge

Status: DOC-09 product policy approved on 2026-08-07 and implemented
fail-closed. Production purge remains prohibited until the deployment evidence
listed below is peer reviewed.

## Safety boundary

Normal deletion is recoverable. The service retains the document text, metadata
and exported DOCX/PDF objects for the configured recovery window. Irreversible
purge is never performed by the scheduled maintenance job and is unavailable
unless all of these conditions are true:

1. `DOCUMENT_STORE_PURGE_ENABLED=true`;
2. `DOCUMENT_STORE_RETENTION_POLICY_VERSION` names an approved policy rather
   than `UNAPPROVED`;
3. the caller uses the dedicated retention-administrator service credential
   and supplies the owner binding;
4. the document is already `DELETED` and its recovery deadline has passed;
5. no legal hold is active;
6. Application Tracker returns an authoritative owner-scoped association
   snapshot immediately before deletion;
7. Tracker accepts the terminal `PURGED` availability projection and scrubs
   complete hashes and evidence details; and
8. no unresolved `PREPARED` storage operation exists.

The purge command is owner-scoped and retry-safe. It removes file objects and
file metadata, scrubs document content, filenames, complete hashes, generation
and evidence payloads, and retains only an owner-visible `PURGED` tombstone,
content-free lifecycle audit and exact application/freeze associations. It
never redirects an application. The service has no bulk purge endpoint.

Coordinated account deletion first uses the recoverable path. Once every
document has passed that deadline, the separately authorized exact-owner
workflow in
[`ACCOUNT_PERMANENT_ERASURE.md`](ACCOUNT_PERMANENT_ERASURE.md) removes live
owner records and all current/noncurrent object versions. Its completion state
truthfully distinguishes live-data erasure from bounded managed-backup expiry.
It retains a keyed pseudonymous operation/write guard, hashed approval and
backup evidence, and the minimum exact opaque document/upload scopes required
to re-erase a restored object store. No raw owner, filename, document content,
approval reference, backup reference or object bytes remain after completion.

Association state alone does not retain bytes forever. While an application is
inside the supported history window, its exact version identity is protected;
after an approved purge, that protection is the scrubbed tombstone and frozen
association rather than document content. Until the supported-history boundary
and production controls are evidenced, operators must not enable production
purge.

## Approved policy periods

| Record | Approved period | Rule |
| --- | ---: | --- |
| Soft-deleted document and its files | 30 days | Recoverable; purge only by the guarded administrator command |
| Completed `COMMITTED`/`ROLLED_BACK` storage journal | 90 days | May be batch-removed only after approved maintenance is enabled |
| Unresolved `PREPARED` storage journal | No age-based deletion | Keep until reconciliation or reviewed manual resolution |
| Lifecycle transition audit | 365 days | Content-free event; batch removal only after approved maintenance is enabled |
| Reconciliation cursors | Indefinite | Retain position; reset to the start only after a completed end-of-scan page |
| Application-used document identity | While supported application history exists | Preserve exact family/version/type and draft/frozen association; never redirect; bytes may be removed only through the peer-approved guarded process |

The periods, user copy and peer-approved legal-hold/purge procedure were
approved on 2026-08-07. Infrastructure must still evidence backup expiry,
object-version expiry, log expiry, administrator deployment and operational
access before production purge can be enabled.

## Maintenance boundary

`DOCUMENT_STORE_RETENTION_MAINTENANCE_ENABLED` defaults to `false`. Even when
enabled, maintenance fails closed unless an approved policy version is set.
Each run deletes at most `DOCUMENT_STORE_RETENTION_BATCH_SIZE` expired
completed journal rows and the same maximum number of expired lifecycle
events. It never selects documents, file objects, `PREPARED` operations or
reconciliation cursors.

The scheduler reports aggregate counts only through
`document_store_retention_maintenance_runs_total` and one redacted log line.
It does not emit owner IDs, document IDs, filenames, object keys or content.
Production log retention and access are platform controls and must be aligned
with the approved policy.

## Legal hold and support access

Legal hold is available only to the dedicated retention-administrator identity:

- `PATCH /api/v1/documents/{id}/legal-hold` with
  `{"active":true,"reference":"<approved-case-reference>"}` applies a hold.
- The same endpoint with `active:false` releases it and requires a case
  reference for the reviewed release decision.
- A held document cannot be soft deleted or purged.
- Applying or releasing a hold creates an internal lifecycle event with actor,
  policy version, time and the non-content case reference. The owner-facing
  lifecycle response and application logs do not expose that reference.

The administrator credential is deliberately denied normal document reads and
normal user deletion. It can only apply/release holds and invoke the guarded
purge endpoint with an explicit owner binding. Operators must never inspect or
copy document content to diagnose a lifecycle request.

Support procedure:

1. Verify the requester through the approved support identity process; never
   accept a document UUID as proof of ownership.
2. Record a non-content case reference and the requested operation.
3. For recovery, instruct the authenticated user journey to restore the item.
   Do not make it current automatically.
4. For a hold, require the documented privacy/legal approval before using the
   retention-administrator command.
5. For purge, confirm every guard above, the matching backup consequences and
   peer approval. Record policy version, case reference, operator and aggregate
   outcome outside application logs.
6. If any store, reference, hold or backup fact is uncertain, stop. Do not
   bypass a guard or alter database state manually.

## Approved user-facing copy

The client work is a separate dependency. It should use equivalent plain
language and must display the server deadline rather than calculating one:

- Archive: “Archive this document? It will no longer be selected for new
  applications. You can restore it later.”
- Archived: “This document is archived. Restore it before using or editing
  it.”
- Delete: “Move this document to Deleted? You can restore it until
  **{purgeEligibleAt}**. Files will not be available while it is deleted.”
- Deleted: “This document is deleted but recoverable until
  **{purgeEligibleAt}**. Restoring it will not select it as your current
  document.”
- Irreversible deletion: “After the recovery period and required retention
  checks, deletion may become permanent. Copies already downloaded by you are
  not controlled by Job Seeker Copilot.”
- Associated version: “This version is selected for a draft application or was
  used for a submitted application. Archive is recommended. Deleting it will
  not switch those applications to another version.”
- Purged tombstone: “This document’s content is no longer available. Its exact
  version and application history are kept so your records remain accurate.”

The UI must not promise a production purge date until cross-service
application-link verification and backup expiry are deployed.

## Backups, exports and incomplete work

Application purge cannot erase bytes already present in a managed backup or S3
object version. Infrastructure must expire those recovery copies according to
the approved backup schedule and restrict restore access. A restore containing
a previously purged record must be isolated, reconciled against the purge
audit, and prevented from re-entering normal traffic until privacy review has
approved the result.

Document Store owns exported DOCX/PDF objects that are still in its private
object store and removes them with the document purge. A file already
downloaded by a user is outside this service. Reporting Service owns generated
reports and must apply its own approved retention policy; Document Store does
not delete them by inference.

`PREPARED` storage operations are recovery evidence and never age out through
retention maintenance. A purge is blocked while one exists for the document.
Unknown object-store orphans are reported and never automatically deleted.
The guarded recovery process is in
[`STORAGE_RECONCILIATION.md`](STORAGE_RECONCILIATION.md).

## Production approval checklist

- Product/legal/privacy approval dated 2026-08-07 is linked to DOC-09.
- Store/Tracker integration proves immutable exact identity, draft/frozen
  association capture and terminal hash/evidence scrubbing.
- Infrastructure injects and rotates a distinct administrator credential.
- Infrastructure pins the independent permanent-erasure capability, grants
  exact-prefix live-object version enumeration/deletion, provisions the
  separate immutable recovery journal, and retains the complete fingerprint
  key ring through the audit/write-guard lifetime.
- Infrastructure pins the exact backup-retention maximum and policy version;
  a retention administrator records hashed expiry evidence only after the
  snapshotted window ends.
- PostgreSQL, S3 object versions, backups, logs and exported reports have
  compatible expiry and restore handling.
- A database-restore exercise proves the retention-admin replay reconstructs
  missing or legacy journal state, preserves neighbouring owners, re-erases
  exact scopes and requires fresh backup-expiry evidence.
- Cross-user archive, restore, delete and purge denial pass in integrated E2E.
- A synthetic production-like recovery/purge exercise proves that no content
  appears in logs or evidence.
- Only then is production purge enabled with the reviewed policy version.

# Document retention, recovery and purge

Status: repository policy proposal implemented fail-closed; product, legal,
privacy and production-platform approval remain required.

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
6. the Store row has no application link; and
7. no unresolved `PREPARED` storage operation exists.

The purge command is owner-scoped and retry-safe. It removes file objects,
file metadata, document text and document metadata. A bounded `PURGED` lifecycle
event is retained without document content so an authorized retry can succeed
without recreating state. The service has no bulk purge endpoint.

The local application-link check is deliberately conservative but not yet
sufficient cross-service evidence. Product enablement also requires APP-08 and
DOCGEN-14 integration to prove that Application Tracker's immutable references
cannot be bypassed. Keep production purge disabled until that evidence exists.

## Proposed periods requiring approval

The repository defaults are safety-oriented proposals, not legal decisions:

| Record | Proposed default | Rule |
| --- | ---: | --- |
| Soft-deleted document and its files | 30 days | Recoverable; purge only by the guarded administrator command |
| Completed `COMMITTED`/`ROLLED_BACK` storage journal | 90 days | May be batch-removed only after approved maintenance is enabled |
| Unresolved `PREPARED` storage journal | No age-based deletion | Keep until reconciliation or reviewed manual resolution |
| Lifecycle transition audit | 365 days | Content-free event; batch removal only after approved maintenance is enabled |
| Reconciliation cursors | Indefinite | Retain position; reset to the start only after a completed end-of-scan page |
| Application-used document versions | Until the supported history policy permits removal | Never silently overwrite; local application link blocks purge |

Product, legal and privacy owners must approve the periods, supported
application-history duration, legal-hold process, account-closure trigger and
the wording below. Infrastructure must separately approve backup expiry,
object-version expiry and operational access.

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

## User-facing copy for product review

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
- Protected history: “This version is linked to application history and cannot
  be permanently removed through this action.”

The UI must not promise a production purge date until product/legal approval,
cross-service application-link verification and backup expiry are deployed.

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

- Product/legal/privacy have approved periods, triggers, user copy, application
  history and legal-hold procedures.
- DOCGEN-14/APP-08 integration proves immutable application links.
- Infrastructure injects and rotates a distinct administrator credential.
- PostgreSQL, S3 object versions, backups, logs and exported reports have
  compatible expiry and restore handling.
- Cross-user archive, restore, delete and purge denial pass in integrated E2E.
- A synthetic production-like recovery/purge exercise proves that no content
  appears in logs or evidence.
- Only then are maintenance and purge enabled with the reviewed policy version.

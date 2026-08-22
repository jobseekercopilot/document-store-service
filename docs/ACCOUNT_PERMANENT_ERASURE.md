# Account document permanent erasure

Status: implemented fail-closed; production capability disabled until the
matching Infrastructure revision, IAM policy and retention evidence are
reviewed.

## Trust boundary

Permanent erasure is an internal retention-administrator operation. It is not
available to the browser, BFF, normal users, producers, readers or account
lifecycle JWTs. The caller must supply the dedicated `X-Service-Token`, one
`X-Document-Owner`, a stable operation UUID, the exact current owner document
IDs and a bounded approval reference. The service never accepts an owner ID as
authority and never offers a bulk or all-owner delete.

```text
PUT /internal/retention/v1/permanent-erasures/{operationId}
X-Service-Token: <retention administrator credential>
X-Document-Owner: <exact owner>

{
  "documentIds": ["<exact UUID>"],
  "approvalReference": "<reviewed non-content reference>"
}
```

The same owner, operation and request are replay-safe. Reusing an operation for
a different owner, document set or approval reference fails. A different
operation for an owner whose erasure has begun also fails. Status is available
from the matching owner-scoped `GET` route. Aggregate release readiness is
available at `/internal/retention/v1/permanent-erasures/readiness`; it never
returns owner, document, object or approval identifiers.

After the server-owned backup window has elapsed, the retention administrator
must separately attest the reviewed platform evidence:

```text
PUT /internal/retention/v1/permanent-erasures/{operationId}/backup-expiry-attestation
X-Service-Token: <retention administrator credential>
X-Document-Owner: <exact owner>

{ "evidenceReference": "<bounded operator evidence reference>" }
```

The reference is stored only as SHA-256 and is never returned or logged.
Attestation before the operation's snapshotted deadline, with a changed
backup-policy version/maximum, or with different replay evidence fails closed.

After a managed database restore, the retention administrator uses the
externally retained operation ID and a new runbook replay ID:

```text
PUT /internal/retention/v1/permanent-erasures/{operationId}/restore-replays/{restoreReplayId}
X-Service-Token: <retention administrator credential>
X-Document-Owner: <exact owner recovered from the protected journal/runbook>

{ "evidenceReference": "<bounded restore incident/runbook reference>" }
```

The replay ID and evidence reference are idempotent: the same pair returns the
current result, while changing the evidence for that replay ID fails. The
evidence reference is stored only as SHA-256. A producer token is forbidden,
and the wrong owner receives the same not-found result as a missing operation.

## Required state and concurrency

Before creating the durable operation, the service takes the owner lifecycle
lock and proves that:

- the feature, normal purge, approved policy and version-erasure capability are
  all explicitly enabled;
- the supplied IDs exactly equal every Document Store row for that owner;
- each row is already `DELETED` past its recovery deadline or already a
  content-free `PURGED` tombstone;
- no legal hold or `PREPARED` storage operation exists;
- every application upload is terminal; and
- file, upload and journal evidence belongs to that exact document set.

Document, file, upload, activity, legacy migration and reconciliation writes
take the same owner lock and reject an existing erasure fingerprint. Upload
quarantine writes and state transitions re-read authoritative state under that
lock, so a timed-out stale worker cannot recreate a row or object after
cleanup/erasure. This prevents a late producer or background process from
recreating data during or after erasure. The completed pseudonymous operation
is retained as the permanent write-revocation guard.

## Recovery journal, database and object ordering

Migration V13 creates one durable operation and exact object scopes. Migration
V14 adds the recovery-journal binding and a separate durable restore-request
table. A document scope is always
`documents/{validated-owner-document-uuid}/`; an upload scope is always the
canonical `quarantine/application-uploads/{validated-owner-upload-uuid}` key.
The storage adapters reject broad, foreign and malformed permanent-delete
scopes.

The initial transaction commits `JOURNAL_PENDING`, the raw owner needed for
recovery, and the exact scopes before making any S3 call. The service then
canonicalises a versioned v1 recovery record and conditionally writes only
`permanent-erasures/v1/{operationId}.json` to the dedicated immutable journal
bucket. The record contains the operation ID, raw owner, exact sorted
document/object scopes, request and approval hashes, snapshotted policy
versions/backup days and creation time. It never contains document content or
a raw approval/evidence reference.

A successful write must return a version ID and exact content SHA-256. The
service reads and validates the SSE-KMS object, metadata digest and canonical
bytes before it commits the key/version/hash binding and advances to
`OBJECT_ERASURE_PENDING`. A conditional-write collision, response loss with no
exact readable match, wrong KMS key, malformed record or content mismatch
leaves `JOURNAL_PENDING`. The scheduler retries it, and no live object or row
can be deleted first.

For S3, every current version, noncurrent version and delete marker in each
scope is enumerated and deleted with its version ID. The adapter then enumerates
again and treats any remaining version, denied list/delete call, ambiguous page
or non-progressing pagination as a retryable storage failure. It never requests
governance-retention bypass.

Only after every exact scope is proven empty does one database transaction
delete owner-scoped files, storage journals, uploads, lifecycle/activity
events, commands, associations and documents. A storage failure leaves the
operation `OBJECT_ERASURE_PENDING` and preserves all database data for retry.
A database rollback leaves the object deletion safe to replay. The bounded
scheduler resumes pending operations after restart.

The durable operation stores the operation UUID, keyed owner fingerprint,
non-secret key verifier, request/approval/evidence hashes, fixed service-role
identity, journal object key/version/digest, counts, policy versions and timestamps.
The raw recovery record is never returned or logged. Application logs and metrics
are aggregate-only: they contain no owner ID, document UUID, storage key,
approval reference, evidence reference, filename or content.

## Backup truth

Removing live PostgreSQL rows and all live S3 versions does not erase encrypted
managed backups immediately. The response therefore becomes
`BACKUP_RETENTION_PENDING`, sets `liveDataErased=true`,
`backupCopiesMayRemain=true` and returns the server-owned
`backupRetentionUntil`. It must not be described as fully complete.

Elapsed local time is not enough to complete an operation. At or after that
deadline a retention administrator must provide the bounded evidence reference
for the snapshotted `DOCUMENT_STORE_BACKUP_RETENTION_POLICY_VERSION` and exact
`DOCUMENT_STORE_MAXIMUM_BACKUP_RETENTION_DAYS` (1-35 days). Only then does the
reconciler delete and verify every exact object scope again before recording
`COMPLETED` and setting `backupCopiesMayRemain=false`. Until then readiness is
`RECONCILIATION_REQUIRED`.

A database or object restore containing erased data must remain isolated. The
operator must invoke the dedicated restore-replay command for every externally
retained operation before allowing restored infrastructure to serve traffic.
A restore from before the operation reconstructs the operation and exact scopes
from the canonical journal. A legacy restored operation without its V14 binding
is non-ready and cannot be scheduled or used to start another erasure; only an
exact matching external journal can repair it. A replay against a retained
completed operation re-enumerates the same scopes. Before reading the external
journal, the service commits a pseudonymous durable restore request containing
the operation/replay IDs, owner fingerprint/key verifier and hashed operator
evidence. A failed journal read or crash is therefore counted by
`restoreJournalReadPending` and retried by the scheduler. Once the exact
journal is validated, one transaction moves the operation to
`RESTORE_REPLAY_PENDING` and removes the request. A later failure remains in
that operation state for scheduler retry. Both states keep release readiness
false. While the external journal read is pending, the owner-scoped status
reports `RESTORE_JOURNAL_READ_PENDING` and withholds the stale prior completion,
live-erasure and backup-expiry claims because restored data may exist. Neither
the durable request nor API response contains the raw journal owner or scopes.

After restored objects and rows are erased, the old completion and backup
attestation are cleared. The operation enters a new `BACKUP_RETENTION_PENDING`
horizon measured from that restore erasure. Only fresh evidence provided after
the new deadline can return it to `COMPLETED`. Exact scopes are therefore
operational restore evidence and are not deleted at completion. The database
scopes contain opaque UUID paths but no raw owner or content; the raw owner is
confined to the protected external journal. Neither this service nor a
successful HTTP response can erase a user-downloaded copy or a report owned by
another service.

## Production capability and IAM

Production remains disabled unless all of these are explicit:

- `DOCUMENT_STORE_PURGE_ENABLED=true`
- `DOCUMENT_STORE_PERMANENT_ERASURE_ENABLED=true`
- `DOCUMENT_STORE_PERMANENT_ERASURE_WRITE_FENCE_ENABLED=true`
- `DOCUMENT_STORE_VERSIONED_OBJECT_ERASURE_ENABLED=true`
- a reviewed `DOCUMENT_STORE_RETENTION_POLICY_VERSION`
- a reviewed `DOCUMENT_STORE_BACKUP_RETENTION_POLICY_VERSION` matching the
  platform recovery policy
- `DOCUMENT_STORE_ERASURE_FINGERPRINT_KEY`, 32-512 base64url characters and
  distinct from `DOCUMENT_STORE_RETENTION_ADMIN_TOKEN`
- optional secret `DOCUMENT_STORE_ERASURE_FINGERPRINT_PREVIOUS_KEYS`, a
  comma-delimited list of at most eight distinct 32-512 character base64url
  keys retained during rotation
- `DOCUMENT_STORE_ERASURE_JOURNAL_PROVIDER=s3`
- `DOCUMENT_STORE_ERASURE_JOURNAL_REGION`, a dedicated bucket in
  `DOCUMENT_STORE_ERASURE_JOURNAL_BUCKET`, and a distinct
  `DOCUMENT_STORE_ERASURE_JOURNAL_KMS_KEY_ID` containing the exact key ARN S3
  returns with read-back encryption evidence (not an alias)
- `DOCUMENT_STORE_ERASURE_JOURNAL_CREDENTIALS_PROVIDER=task-role`
- `DOCUMENT_STORE_ERASURE_JOURNAL_OBJECT_LOCK_ENABLED=true` and a reviewed
  `DOCUMENT_STORE_ERASURE_JOURNAL_RETENTION_POLICY_VERSION` sourced from the
  distinct launch-approval field bound to the immutable journal lifecycle and
  Object Lock evidence; the value is 1-128 characters matching
  `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`
- `DOCUMENT_STORE_MAXIMUM_BACKUP_RETENTION_DAYS`, bounded to 1-35
- PostgreSQL, reconciliation, S3, task-role credentials and SSE-KMS production
  storage checks already required by the service

The fingerprint keys, external journal and write-fence flag are audit-critical.
Keep them mounted after disabling initiation and across restores. Every
operation stores a non-secret key verifier. New operations use only the primary
key; retained operations remain discoverable through configured previous keys.
Startup, readiness and owner operations compare every retained verifier with
the configured ring. Removing a previous key while its evidence remains blocks
readiness and operations instead of silently missing an erased owner. Complete
the reviewed retention or migration of all evidence protected by a previous
key before removing that key. Secrets are not returned by readiness or
application APIs and must not be logged.

The task role needs `s3:ListBucketVersions` restricted by `s3:prefix` to
`documents/*` and `quarantine/application-uploads/*`, plus
`s3:DeleteObjectVersion` on only those two object ARN prefixes. Do not grant
`DeleteBucket`, bucket-policy mutation, backup-vault deletion,
`BypassGovernanceRetention`, object-lock or legal-hold permissions.

For the separate journal bucket, the Document Store task needs only
`s3:PutObject`, `s3:GetObject` and `s3:GetObjectVersion` on
`permanent-erasures/v1/*`. It does not need bucket listing or any journal
delete, bypass or bucket-policy permission. S3 writes use `If-None-Match: *`,
SHA-256 checksum/metadata, SSE-KMS with the exact journal key and bucket keys.
The KMS grant is limited to `kms:GenerateDataKey` and `kms:Decrypt` via S3 for
that bucket/prefix encryption context. The task does not call `kms:Encrypt` or
`kms:DescribeKey`. Infrastructure
owns versioning, Object Lock and reviewed lifecycle retention. The release
operator does not read or write journal objects directly; it calls the
retention-administrator API.

Release automation must keep initiation, purge and version erasure false until
the pinned Document Store revision/OpenAPI hash is recorded under the
independently reviewed `documentStorePermanentErasureVerified` capability.
Once any operation exists, the write-fence, matching key ring and journal must
remain configured even if new erasures are disabled. Readiness reports
`schemaVersion=document-permanent-erasure-readiness.v2` and the aggregate fields
`recoveryJournalWritePending`, `recoveryJournalEvidenceMissing`,
`liveErasureReconciliationPending`, `restoreJournalReadPending`,
`restoreReplayPending` and `backupRetentionPending` without raw identifiers.
Every count must be zero for release. Credentials alone never enable the
feature.

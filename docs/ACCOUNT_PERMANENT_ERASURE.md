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

## Database and object ordering

Migration V13 creates one durable operation and exact object scopes. A document
scope is always `documents/{validated-owner-document-uuid}/`; an upload scope is
always the canonical `quarantine/application-uploads/{validated-owner-upload-uuid}`
key. The storage adapters reject broad, foreign and malformed permanent-delete
scopes.

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
identity, counts, policy versions and timestamps. Application logs and metrics
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
operator must replay the externally retained deletion journal for every
operation before allowing restored infrastructure to serve traffic. A restore
from before the operation recreates the same exact operation and erases it
again; a replay against a retained completed operation re-enumerates and
deletes its retained exact document/upload scopes again. Exact scopes are
therefore operational restore evidence and are not deleted at completion.
They contain opaque UUID paths but no raw owner or content. Neither this
service nor a successful HTTP response can erase a user-downloaded copy or a
report owned by another service.

## Production capability and IAM

Production remains disabled unless all of these are explicit:

- `DOCUMENT_STORE_PURGE_ENABLED=true`
- `DOCUMENT_STORE_PERMANENT_ERASURE_ENABLED=true`
- `DOCUMENT_STORE_PERMANENT_ERASURE_WRITE_FENCE_ENABLED=true`
- `DOCUMENT_STORE_VERSIONED_OBJECT_ERASURE_ENABLED=true`
- a reviewed `DOCUMENT_STORE_RETENTION_POLICY_VERSION`
- a reviewed `DOCUMENT_STORE_BACKUP_RETENTION_POLICY_VERSION` matching the
  platform recovery policy
- `DOCUMENT_STORE_ERASURE_FINGERPRINT_KEY`, 32-512 non-control characters and
  distinct from `DOCUMENT_STORE_RETENTION_ADMIN_TOKEN`
- `DOCUMENT_STORE_MAXIMUM_BACKUP_RETENTION_DAYS`, bounded to 1-35
- PostgreSQL, reconciliation, S3, task-role credentials and SSE-KMS production
  storage checks already required by the service

The fingerprint key and write-fence flag are audit-critical. Keep both mounted
after disabling initiation and across restores. Every operation stores a
non-secret key verifier. If the key is absent while operations exist, or the
verifier changes, all document writes fail closed rather than silently missing
an erased owner. Rotation requires a reviewed migration that recomputes every
retained owner-erasure fingerprint and verifier before the new key is used.

The task role needs `s3:ListBucketVersions` restricted by `s3:prefix` to
`documents/*` and `quarantine/application-uploads/*`, plus
`s3:DeleteObjectVersion` on only those two object ARN prefixes. Do not grant
`DeleteBucket`, bucket-policy mutation, backup-vault deletion,
`BypassGovernanceRetention`, object-lock or legal-hold permissions.

Release automation must keep initiation, purge and version erasure false until
the pinned Document Store revision/OpenAPI hash is recorded under the
independently reviewed `documentStorePermanentErasureVerified` capability.
Once any operation exists, the write-fence flag and stable fingerprint key must
remain configured even if new erasures are disabled. Credentials alone never
enable the feature.

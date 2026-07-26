# Document storage reconciliation

## Purpose and boundary

Document Store coordinates relational metadata in PostgreSQL with encrypted
DOCX/PDF objects in the configured object store. Those systems cannot share one
transaction. The durable operation journal and bounded reconciler make their
partial failures observable and safely retryable without guessing which
document belongs to a user.

This repository owns Store metadata/object recovery only. Generation request
orchestration remains with DOCGEN-09, multi-format export coordination remains
with Document Export, and application/document link recovery remains with
Application Tracker.

## Write and recovery states

Before an object write, Document Store commits a `PREPARED` journal entry with
the stable file ID, version, object key, request fingerprint and optional
idempotency key. The object write then uses that reserved key. File metadata and
the transition to `COMMITTED` share the caller's PostgreSQL transaction.

| Observed state | Meaning | Safe action |
| --- | --- | --- |
| `PREPARED` plus file metadata | The metadata transaction committed but the journal transition needs repair | Mark the journal `COMMITTED`; normal metadata inspection verifies the object |
| `PREPARED` without file metadata | The write did not reach a committed metadata result | Delete the reserved object key idempotently and mark the journal `ROLLED_BACK` |
| `DELETE_PENDING` file metadata | A lifecycle delete committed before object removal completed | Delete the named object idempotently, then delete its metadata |
| `AVAILABLE` metadata with no object | The advertised file cannot be served | Mark it inactive and `UNAVAILABLE`; never recreate or relink content by inference |
| `AVAILABLE` metadata with the wrong size, SHA-256 or safe file structure | The object is corrupt or unsafe | Mark it inactive and `UNAVAILABLE` |
| Object under `documents/` with neither metadata nor a `PREPARED` reservation | An unknown orphan was detected | Count and investigate it; never delete it automatically |

`COMMITTED` and `ROLLED_BACK` journal rows do not hide unknown objects. If their
metadata is absent but an object later appears, inventory treats it as an
unknown orphan.

## Bounded and concurrent execution

Each pass takes a transaction-scoped global reconciliation lock, so only one
worker mutates reconciliation state at a time. Every category processes at most
`batch-size` records. Independent persisted cursors advance across failed as
well as successful records, preventing one persistent outage from starving the
rest of the inventory. A cursor resets after reaching the end, so later passes
revisit transient failures and newly eligible records.

The minimum-age delay prevents reconciliation from racing a normal in-flight
write. Object deletion is idempotent. If PostgreSQL fails after an object-side
action, the durable database state remains eligible and the next pass safely
repeats that action.

The scheduler is enabled by default. Tests disable it and invoke the reconciler
directly.

| Setting | Default | Purpose |
| --- | ---: | --- |
| `DOCUMENT_STORE_RECONCILIATION_ENABLED` | `true` | Required by the production safety verifier |
| `DOCUMENT_STORE_RECONCILIATION_RUN_ON_STARTUP` | `false` | Run one pass when an instance starts |
| `DOCUMENT_STORE_RECONCILIATION_BATCH_SIZE` | `50` | Per-category upper bound, maximum 500 |
| `DOCUMENT_STORE_RECONCILIATION_MINIMUM_AGE_SECONDS` | `300` | Age before mutable rows become eligible |
| `DOCUMENT_STORE_RECONCILIATION_INITIAL_DELAY_MS` | `60000` | Delay before the scheduled worker starts |
| `DOCUMENT_STORE_RECONCILIATION_FIXED_DELAY_MS` | `300000` | Delay between completed passes |

The object prefix is fixed to the opaque application namespace `documents/`.
Do not widen it to the whole bucket.

## AWS deployment requirements

The intended AWS deployment should grant the service an ECS/Fargate task role
through the AWS SDK default credential chain. Removing the current transitional
static access-key settings and proving the task-role binding remain tracked by
Infrastructure issue INFRA-08.

The role needs only:

- `s3:ListBucket` on the private Document Store bucket with a
  `s3:prefix` condition restricted to `documents/`;
- object get, put and delete permissions restricted to `documents/*`;
- the minimum KMS data-key/encrypt/decrypt permissions required for the
  configured bucket key; and
- no public ACL, bucket-policy mutation or unrelated bucket access.

S3 object listing is prefix-scoped, lexicographically paged and bounded. Every
new object write requests SSE-KMS. AWS CloudTrail, S3 access evidence and KMS
key controls are platform evidence; the repository tests do not claim a
deployed AWS control.

## Observability and privacy

Each completed pass emits one aggregate log message. It contains counts only:
completed/failed pending deletes, committed/rolled-back/failed prepared writes,
inspected/quarantined/failed metadata, unknown orphans and inventory failures.
It never includes owner IDs, document IDs, filenames, object keys or content.

The counter `document_store_reconciliation_items_total` uses only the fixed
`outcome` values represented by those counts. The scheduler also increments
`document_store_reconciliation_runs_total` with the fixed outcome `completed`
or `failed`, including passes that find no work. Alerting should cover:

- any `metadata_quarantined`, `unknown_orphan_detected` or
  `inventory_failed` result;
- repeated `delete_pending_failed`, `prepared_failed` or
  `metadata_inspection_failed` results; and
- absence of successful reconciliation completion over the agreed interval.

Alert thresholds and CloudWatch routing remain deployment work.

## Operator runbook

1. Stop destructive manual activity. Do not mark a row `AVAILABLE`, invent a
   metadata link or delete an unknown object.
2. Confirm PostgreSQL and S3 health, the configured bucket/prefix, KMS access
   and the reconciliation aggregate counters. Do not print credentials, object
   keys or document data into tickets or logs.
3. For a normal retry, restore the dependency and allow the next scheduled
   pass. To force a controlled pass, roll one instance with
   `DOCUMENT_STORE_RECONCILIATION_RUN_ON_STARTUP=true`, capture aggregate
   evidence, then return the setting to `false`.
4. A quarantined file stays unavailable. Recover it only from a verified,
   matching database/object-store recovery point under the storage restore
   runbook; never substitute a different object with the same format.
5. For an unknown orphan, use restricted operator tooling to prove all of:
   there is no file metadata, no `PREPARED` journal reservation, no active
   restore/replication operation, and no legal-hold or incident requirement.
   Require peer approval and an encrypted recovery point before a narrowly
   targeted deletion. The application intentionally exposes no bulk orphan
   deletion endpoint.
6. Record only aggregate counts, time window, environment, recovery-point
   reference, approval and outcome. Escalate repeated or unexplained
   discrepancies as a storage/privacy incident.

## Verification

The repository suite covers journal commit, prepared-write cleanup and retry,
pending-delete completion, missing/corrupt metadata quarantine, transient
storage outages, unknown-orphan non-deletion, bounded filesystem and S3
inventory paging, PostgreSQL migration/restart recovery, concurrent file
version allocation and operation-key replay. The production deployment must
still prove task-role permissions, private S3/KMS controls, CloudWatch alerts
and paired PostgreSQL/S3 restore behavior.

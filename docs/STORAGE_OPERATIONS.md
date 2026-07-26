# Document storage operations

## Production topology and fail-closed boundary

Document Store uses PostgreSQL for document metadata and generated text. DOCX
and PDF bytes are stored behind `DocumentObjectStorage`; the production adapter
targets a private S3-compatible bucket and every write requests managed
SSE-KMS encryption. The database stores only the opaque object key, owner,
document link, file type, safe filename, MIME type, byte length, SHA-256,
version, lifecycle status and timestamps.

The default runtime configuration has no local database or filesystem fallback.
Startup fails unless all of the following are true:

- the JDBC target is PostgreSQL;
- database username and password are injected;
- database TLS uses `sslmode=verify-full`;
- Flyway is enabled, Flyway clean is disabled and Hibernate is validate-only;
- the H2 console and SQL logging are disabled;
- managed encryption at rest and encrypted backups are declared; and
- non-secret managed key references are supplied for the database and backups;
- the object provider is `s3`, its region and private bucket are named, and
  credentials are injected; and
- a managed object KMS key is named and any custom endpoint uses HTTPS.

The two key-reference settings are identifiers and must never contain key
material. They make absent platform ownership fail closed; the repository
cannot attest that a cloud control plane actually applied the named keys.
Infrastructure must retain deployment evidence for secret injection, bucket
privacy/public-access blocking, bucket policy, key ownership and rotation,
database/backup encryption, object versioning and object backup/replication.
That external deployment evidence remains owned by INFRA-08. The bounded
database/object-store recovery rules are defined in
[`STORAGE_RECONCILIATION.md`](STORAGE_RECONCILIATION.md).

## Required runtime settings

The non-secret names and example shapes are in [`.env.example`](../.env.example).
Production values must come from the approved secret manager and deployment
configuration, not an environment file in the repository.

The database role must be dedicated to this service. It needs connect and
normal DML access plus only the DDL required for reviewed Flyway migrations.
Human users and other services must not share it. Credential rotation is a
deployment operation: inject the replacement credential, roll the service,
verify health and revoke the former credential.

The object-store principal must be dedicated to this service and restricted to
the configured bucket. It needs get/put/delete/head for `documents/*` and
bucket-level listing restricted by an S3 prefix condition to `documents/`. It
must not grant public ACL or bucket-policy mutation. The intended AWS
production binding is an ECS/Fargate task role rather than a long-lived access
key; that Infrastructure migration remains tracked by INFRA-08. Rotate any
transitional credentials and the KMS key reference through the approved
secret/infrastructure workflow. The object key contains opaque UUIDs, not
usernames, filenames or document text.

## Migration and release procedure

1. Confirm the backup schedule and latest restore drill are within the agreed
   recovery objectives.
2. Take an encrypted pre-release backup and record its immutable identifier.
3. Review every new `src/main/resources/db/migration/common/V*__*.sql` file.
   Applied migrations are never edited or renamed.
4. Run `mvn -B --no-transfer-progress clean verify`. CI runs the same migration
   against H2 in PostgreSQL compatibility mode and a real PostgreSQL container.
5. Confirm the target private bucket, KMS key and credentials are available.
6. Deploy one instance. `ProductionStorageVerifier` validates both storage
   boundaries before Flyway can migrate, then Hibernate validates the result.
7. On an upgrade from schema V1, startup copies each `LEGACY_DATABASE` BYTEA to
   the object store, records its size and SHA-256, and only then clears the
   database BLOB. Startup fails closed if any copy cannot be completed.
8. Verify service health, migration history and that no
   `LEGACY_DATABASE` rows remain before completing the rollout.

Flyway migrations are forward-only. Application rollback is allowed only while
the database remains backward compatible. An incompatible database change is
rolled forward with a reviewed corrective migration, or the service is stopped
and the encrypted pre-release backup is restored. `flyway clean` is prohibited.
V1 cannot read a file after V2 has verified its object and cleared the BYTEA.
Once any legacy row is migrated, recover by rolling V2 forward or by restoring
the matching pre-release database and object-store recovery points; do not
deploy the V1 application against the migrated database.

## Backup and restore drill

`PostgresStorageRecoveryIntegrationTest` provides executable synthetic database
and V1-to-V2 migration evidence:

- migrate an empty PostgreSQL database;
- preserve a synthetic legacy BYTEA through the V2 schema migration;
- reject invalid types, orphan files and wrong credentials;
- discard the application-side migration/JDBC state, repeat the service startup
  migration path and verify text plus the file SHA-256;
- create a custom-format backup and restore it into a fresh database;
- validate Flyway history and the restored legacy checksum; and
- delete the synthetic file and document, proving both are absent.

`LegacyDatabaseObjectMigratorTest` proves the byte copy, SHA-256 metadata update
and database-BLOB clearing order. `FileSystemDocumentObjectStorageTest` proves
object recovery across adapter restart and cleanup. `S3DocumentObjectStorageTest`
proves that production writes request SSE-KMS and do not set a public ACL.

CI test data is synthetic. A production restore drill must restore both the
metadata database and the matching object-store recovery point in an isolated
access-controlled environment, encrypted transport and storage, redacted
operator logs, and an approved synthetic/canary record. Record the backup ID,
object-store version/recovery point, key references, schema version, checksum
result, duration, operator and cleanup result without copying document content
into the evidence.

The production operator sequence is:

1. create an isolated empty PostgreSQL database and private recovery bucket;
2. restore the encrypted database and matching object versions;
3. run Flyway validation only;
4. query the approved canary metadata, fetch its private object and compare the
   recorded size and SHA-256;
5. prove the restored endpoints are not reachable by application traffic;
6. delete the canary through the service lifecycle and verify that its object,
   file metadata and document metadata are absent; and
7. destroy both isolated recovery stores according to the retention policy.

## Local and test profile

H2 is a test-scoped dependency only. Maven tests explicitly select an H2
in-memory database in PostgreSQL compatibility mode and a unique temporary
filesystem object root, disable the production safety check and still use the
reviewed Flyway migrations plus Hibernate schema validation. There is no H2
console. The filesystem adapter rejects path traversal and is never accepted by
the production verifier. Do not use it with personal data or as production
persistence/recovery evidence.

## Incident and recovery constraints

- Never log JDBC URLs containing credentials, passwords, tokens, key material,
  SQL bind values, document text, file bytes or personal filenames.
- Stop writes before point-in-time recovery or a full restore.
- Preserve the failed database and audit evidence until incident ownership
  approves disposal.
- Restore into a new database, validate migrations and checksums, then change
  the deployment reference through the approved infrastructure workflow.
- A checksum/size mismatch quarantines metadata as `UNAVAILABLE` and returns a
  stable 503 without returning corrupt bytes.
- Deletion first commits `DELETE_PENDING`, then removes the object and metadata.
  If either store fails, leave the row pending for scheduled reconciliation;
  do not manually mark it available.
- Unknown orphan objects are reported but never automatically deleted. Follow
  the guarded procedure in
  [`STORAGE_RECONCILIATION.md`](STORAGE_RECONCILIATION.md).
- Lifecycle deletion, retention, legal hold and user-facing recovery remain
  owned by DOC-09; this runbook proves only the storage-layer synthetic drill.

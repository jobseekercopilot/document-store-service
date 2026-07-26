# Document storage operations

## Production topology and fail-closed boundary

Document Store uses PostgreSQL for document metadata, generated text and the
current exported-file byte representation. The default runtime configuration
has no local or in-memory database fallback. Startup fails unless all of the
following are true:

- the JDBC target is PostgreSQL;
- database username and password are injected;
- database TLS uses `sslmode=verify-full`;
- Flyway is enabled, Flyway clean is disabled and Hibernate is validate-only;
- the H2 console and SQL logging are disabled;
- managed encryption at rest and encrypted backups are declared; and
- non-secret managed key references are supplied for the database and backups.

The two key-reference settings are identifiers and must never contain key
material. They make absent platform ownership fail closed; the repository
cannot attest that a cloud control plane actually applied the named keys.
Infrastructure must retain deployment evidence for secret injection, key
ownership, rotation and database/backup encryption. That external evidence is
owned by INFRA-08.

Storing file bytes in PostgreSQL is the bounded durable STORE-02 baseline.
DOC-03 owns the approved metadata/object-storage separation, encrypted object
lifecycle and reconciliation. No STORE-02 evidence should be interpreted as
closing DOC-03.

## Required runtime settings

The non-secret names and example shapes are in [`.env.example`](../.env.example).
Production values must come from the approved secret manager and deployment
configuration, not an environment file in the repository.

The database role must be dedicated to this service. It needs connect and
normal DML access plus only the DDL required for reviewed Flyway migrations.
Human users and other services must not share it. Credential rotation is a
deployment operation: inject the replacement credential, roll the service,
verify health and revoke the former credential.

## Migration and release procedure

1. Confirm the backup schedule and latest restore drill are within the agreed
   recovery objectives.
2. Take an encrypted pre-release backup and record its immutable identifier.
3. Review every new `src/main/resources/db/migration/common/V*__*.sql` file.
   Applied migrations are never edited or renamed.
4. Run `mvn -B --no-transfer-progress clean verify`. CI runs the same migration
   against H2 in PostgreSQL compatibility mode and a real PostgreSQL container.
5. Deploy one instance. `ProductionStorageVerifier` validates the storage
   boundary before Flyway can migrate, then Hibernate validates the result.
6. Verify service health and migration history before completing the rollout.

Flyway migrations are forward-only. Application rollback is allowed only while
the database remains backward compatible. An incompatible database change is
rolled forward with a reviewed corrective migration, or the service is stopped
and the encrypted pre-release backup is restored. `flyway clean` is prohibited.

## Backup and restore drill

`PostgresStorageRecoveryIntegrationTest` provides executable synthetic evidence:

- migrate an empty PostgreSQL database;
- persist synthetic text and bytes;
- reject invalid types, orphan files and wrong credentials;
- discard the application-side migration/JDBC state, repeat the service startup
  migration path and verify text plus the file SHA-256;
- create a custom-format backup and restore it into a fresh database;
- validate Flyway history and the restored checksum; and
- delete the synthetic file and document, proving both are absent.

CI test data is synthetic. A production restore drill must use an isolated
access-controlled environment, encrypted transport and storage, redacted
operator logs, and an approved synthetic/canary record. Record the backup ID,
key reference, schema version, checksum result, duration, operator and cleanup
result without copying document content into the evidence.

The production operator sequence is:

1. create an isolated empty PostgreSQL database;
2. restore the encrypted backup without changing ownership;
3. run Flyway validation only;
4. query the approved canary IDs and compare recorded checksums;
5. prove the restored endpoint is not reachable by application traffic;
6. delete the canary file row, then its document row, and verify absence; and
7. destroy the isolated database according to the retention policy.

## Local and test profile

H2 is a test-scoped dependency only. Maven tests explicitly select an H2
in-memory database in PostgreSQL compatibility mode, disable the production
safety check and still use the reviewed Flyway migration plus Hibernate schema
validation. There is no H2 console. Do not use this profile with personal data
or as persistence/recovery evidence.

## Incident and recovery constraints

- Never log JDBC URLs containing credentials, passwords, tokens, key material,
  SQL bind values, document text, file bytes or personal filenames.
- Stop writes before point-in-time recovery or a full restore.
- Preserve the failed database and audit evidence until incident ownership
  approves disposal.
- Restore into a new database, validate migrations and checksums, then change
  the deployment reference through the approved infrastructure workflow.
- Lifecycle deletion, retention, legal hold and user-facing recovery remain
  owned by DOC-09; this runbook proves only the storage-layer synthetic drill.

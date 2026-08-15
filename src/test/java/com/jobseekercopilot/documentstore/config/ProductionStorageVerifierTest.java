package com.jobseekercopilot.documentstore.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ProductionStorageVerifierTest {
    private static final DefaultApplicationArguments NO_ARGUMENTS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void rejectsNonPostgresProductionDatabase() {
        MockEnvironment environment =
                validEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:unsafe");

        assertThatThrownBy(() -> verifier(environment).run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires PostgreSQL");
    }

    @Test
    void rejectsPostgresWithoutVerifiedTls() {
        MockEnvironment environment = validEnvironment()
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://database.example/documents")
                .withProperty(
                        "spring.datasource.hikari.data-source-properties.sslmode", "require");

        assertThatThrownBy(() -> verifier(environment).run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslmode=verify-full");
    }

    @Test
    void rejectsMissingManagedCredentials() {
        MockEnvironment environment =
                validEnvironment().withProperty("spring.datasource.password", "");

        assertThatThrownBy(() -> verifier(environment).run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    void rejectsMissingEncryptionAtRestEvidence() {
        MockEnvironment environment = validEnvironment()
                .withProperty(
                        "document-store.database.encryption-at-rest-enabled", "false");

        assertThatThrownBy(() -> verifier(environment).run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-at-rest-enabled");
    }

    @Test
    void rejectsMissingBackupKeyReference() {
        MockEnvironment environment = validEnvironment()
                .withProperty("document-store.database.backup-key-reference", "");

        assertThatThrownBy(() -> verifier(environment).run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup-key-reference");
    }

    @Test
    void rejectsFilesystemOrUnencryptedObjectStorageInProduction() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("document-store.object-storage.provider", "filesystem"))
                .run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3-compatible");

        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty(
                                "document-store.object-storage.s3.endpoint",
                                "http://objects.example"))
                .run(NO_ARGUMENTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsSchemaMutationAndDebugPaths() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty("spring.jpa.hibernate.ddl-auto", "update"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("schema mutation");

        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty("spring.h2.console.enabled", "true"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("H2 console");

        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty("spring.jpa.show-sql", "true"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("SQL logging");
    }

    @Test
    void verifiesSafetyBeforeRunningMigrations() {
        Flyway flyway = mock(Flyway.class);

        verifier(validEnvironment()).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void neverRunsMigrationsForUnsafeStorage() {
        Flyway flyway = mock(Flyway.class);
        MockEnvironment environment =
                validEnvironment().withProperty("spring.flyway.clean-disabled", "false");

        assertThatThrownBy(() -> verifier(environment).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flyway clean");
        verify(flyway, never()).migrate();
    }

    @Test
    void acceptsProductionSafeConfiguration() {
        assertThatCode(() -> verifier(validEnvironment()).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTaskRoleCredentialsWithoutStaticSecrets() {
        MockEnvironment environment = validEnvironment()
                .withProperty(
                        "document-store.object-storage.s3.credentials-provider", "task-role")
                .withProperty("document-store.object-storage.s3.access-key", "")
                .withProperty("document-store.object-storage.s3.secret-key", "");

        assertThatCode(() -> verifier(environment).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsStaticSecretsOrCustomEndpointInTaskRoleMode() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty(
                                        "document-store.object-storage.s3.credentials-provider",
                                        "task-role"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("Static S3 credentials are forbidden");

        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty(
                                        "document-store.object-storage.s3.credentials-provider",
                                        "task-role")
                                .withProperty(
                                        "document-store.object-storage.s3.access-key", "")
                                .withProperty(
                                        "document-store.object-storage.s3.secret-key", "")
                                .withProperty(
                                        "document-store.object-storage.s3.endpoint",
                                        "https://objects.example"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("custom S3 endpoint is forbidden");
    }

    @Test
    void rejectsUnknownOrIncompleteS3CredentialProvider() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty(
                                        "document-store.object-storage.s3.credentials-provider",
                                        "ambient"))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("task-role or static");

        assertThatThrownBy(() -> verifier(validEnvironment()
                                .withProperty(
                                        "document-store.object-storage.s3.secret-key", ""))
                        .run(NO_ARGUMENTS))
                .hasMessageContaining("object-storage.s3.secret-key");
    }

    @Test
    void isolatedTestConfigurationCanExplicitlyDisableProductionCheck() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("document-store.database.production-safety-check", "false");

        assertThatCode(() -> verifier(environment).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
    }

    @Test
    void productionPurgeFailsClosedWithoutThePermanentErasureCapability() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("document-store.retention.purge-enabled", "true"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("permanent-erasure capability");
    }

    @Test
    void permanentErasureRequiresVersionDeletionTaskRolePolicyAndDistinctSecrets() {
        assertThatCode(() -> verifier(permanentErasureEnvironment()).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.permanent-erasure-write-fence-enabled",
                                "false"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("permanent write fence");
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.versioned-object-erasure-enabled",
                                "false"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("versioned-object deletion");
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.object-storage.s3.credentials-provider",
                                "static")
                        .withProperty(
                                "document-store.object-storage.s3.access-key",
                                "managed-access")
                        .withProperty(
                                "document-store.object-storage.s3.secret-key",
                                "managed-secret"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("task-role S3 credentials");
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.policy-version",
                                "UNAPPROVED"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("approved retention policy");
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.backup-retention-policy-version",
                                "UNAPPROVED"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("approved backup-retention policy");
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.erasure-fingerprint-key",
                                "retention-administrator-token-000001"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("must be distinct");
    }

    @Test
    void permanentErasureRejectsUnboundedBackupRetention() {
        assertThatThrownBy(() -> verifier(permanentErasureEnvironment()
                        .withProperty(
                                "document-store.retention.maximum-backup-retention-days",
                                "36"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("1-35 days");
    }

    private MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://database.example/documents?sslmode=verify-full")
                .withProperty("spring.datasource.username", "document_store")
                .withProperty("spring.datasource.password", "managed-secret-reference")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.h2.console.enabled", "false")
                .withProperty("spring.jpa.show-sql", "false")
                .withProperty("document-store.reconciliation.enabled", "true")
                .withProperty(
                        "document-store.database.encryption-at-rest-enabled", "true")
                .withProperty(
                        "document-store.database.encryption-key-reference",
                        "kms://document-store/database")
                .withProperty(
                        "document-store.database.backup-encryption-enabled", "true")
                .withProperty(
                        "document-store.database.backup-key-reference",
                        "kms://document-store/backups")
                .withProperty("document-store.object-storage.provider", "s3")
                .withProperty("document-store.object-storage.s3.region", "eu-west-2")
                .withProperty("document-store.object-storage.s3.bucket", "document-objects")
                .withProperty(
                        "document-store.object-storage.s3.credentials-provider", "static")
                .withProperty("document-store.object-storage.s3.access-key", "managed-access")
                .withProperty("document-store.object-storage.s3.secret-key", "managed-secret")
                .withProperty(
                        "document-store.object-storage.s3.kms-key-id",
                        "kms://document-store/objects");
    }

    private MockEnvironment permanentErasureEnvironment() {
        return validEnvironment()
                .withProperty(
                        "document-store.object-storage.s3.credentials-provider",
                        "task-role")
                .withProperty("document-store.object-storage.s3.access-key", "")
                .withProperty("document-store.object-storage.s3.secret-key", "")
                .withProperty("document-store.retention.purge-enabled", "true")
                .withProperty(
                        "document-store.retention.permanent-erasure-enabled",
                        "true")
                .withProperty(
                        "document-store.retention.permanent-erasure-write-fence-enabled",
                        "true")
                .withProperty(
                        "document-store.retention.versioned-object-erasure-enabled",
                        "true")
                .withProperty(
                        "document-store.retention.policy-version",
                        "reviewed-retention-2026-08")
                .withProperty(
                        "document-store.retention.backup-retention-policy-version",
                        "reviewed-backups-35d-v1")
                .withProperty(
                        "document-store.retention.maximum-backup-retention-days",
                        "35")
                .withProperty(
                        "document-store.security.service-identity.retention-admin-token",
                        "retention-administrator-token-000001")
                .withProperty(
                        "document-store.retention.erasure-fingerprint-key",
                        "owner-fingerprint-secret-key-000001");
    }

    private ProductionStorageVerifier verifier(MockEnvironment environment) {
        return new ProductionStorageVerifier(environment);
    }
}

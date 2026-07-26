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
    void isolatedTestConfigurationCanExplicitlyDisableProductionCheck() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("document-store.database.production-safety-check", "false");

        assertThatCode(() -> verifier(environment).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
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
                .withProperty(
                        "document-store.database.encryption-at-rest-enabled", "true")
                .withProperty(
                        "document-store.database.encryption-key-reference",
                        "kms://document-store/database")
                .withProperty(
                        "document-store.database.backup-encryption-enabled", "true")
                .withProperty(
                        "document-store.database.backup-key-reference",
                        "kms://document-store/backups");
    }

    private ProductionStorageVerifier verifier(MockEnvironment environment) {
        return new ProductionStorageVerifier(environment);
    }
}

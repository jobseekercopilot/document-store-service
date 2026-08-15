package com.jobseekercopilot.documentstore.config;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProductionStorageVerifier implements ApplicationRunner, FlywayMigrationStrategy {
    private static final Pattern VERIFIED_TLS_QUERY_PARAMETER =
            Pattern.compile("(?:[?&])sslmode=verify-full(?:&|$)", Pattern.CASE_INSENSITIVE);

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        verifyConfiguration();
    }

    @Override
    public void migrate(Flyway flyway) {
        verifyConfiguration();
        flyway.migrate();
    }

    private void verifyConfiguration() {
        if (!environment.getProperty(
                "document-store.database.production-safety-check", Boolean.class, true)) {
            return;
        }

        String url = required("spring.datasource.url");
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "Document Store requires PostgreSQL outside isolated tests");
        }

        String configuredSslMode =
                environment.getProperty("spring.datasource.hikari.data-source-properties.sslmode", "");
        if (!VERIFIED_TLS_QUERY_PARAMETER.matcher(url).find()
                && !"verify-full".equalsIgnoreCase(configuredSslMode)) {
            throw new IllegalStateException(
                    "Document Store database connections must use sslmode=verify-full");
        }

        required("spring.datasource.username");
        required("spring.datasource.password");
        requireTrue(
                "document-store.database.encryption-at-rest-enabled",
                "Managed database encryption at rest must be declared");
        required("document-store.database.encryption-key-reference");
        requireTrue(
                "document-store.database.backup-encryption-enabled",
                "Encrypted database backups must be declared");
        required("document-store.database.backup-key-reference");

        if (!"s3".equalsIgnoreCase(required("document-store.object-storage.provider"))) {
            throw new IllegalStateException(
                    "Production document bytes require the encrypted S3-compatible object-store adapter");
        }
        required("document-store.object-storage.s3.region");
        required("document-store.object-storage.s3.bucket");
        verifyObjectStoreCredentials();
        required("document-store.object-storage.s3.kms-key-id");
        String objectEndpoint = environment.getProperty(
                "document-store.object-storage.s3.endpoint", "");
        if (!objectEndpoint.isBlank()
                && !objectEndpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException(
                    "Document object-store endpoints must use HTTPS");
        }

        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Reviewed Flyway migrations are required for Document Store");
        }
        if (!environment.getProperty(
                "document-store.reconciliation.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Document storage reconciliation must be enabled");
        }
        verifyPermanentErasureConfiguration();
        if (!environment.getProperty("spring.flyway.clean-disabled", Boolean.class, false)) {
            throw new IllegalStateException("Flyway clean must remain disabled");
        }
        if (!"validate".equalsIgnoreCase(required("spring.jpa.hibernate.ddl-auto"))) {
            throw new IllegalStateException(
                    "Hibernate schema mutation is forbidden; use reviewed Flyway migrations");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            throw new IllegalStateException("The H2 console is forbidden outside isolated tests");
        }
        if (environment.getProperty("spring.jpa.show-sql", Boolean.class, false)) {
            throw new IllegalStateException("SQL logging is forbidden outside isolated tests");
        }
    }

    private void verifyPermanentErasureConfiguration() {
        boolean purgeEnabled = environment.getProperty(
                "document-store.retention.purge-enabled", Boolean.class, false);
        boolean permanentErasureEnabled = environment.getProperty(
                "document-store.retention.permanent-erasure-enabled",
                Boolean.class,
                false);
        boolean writeFenceEnabled = environment.getProperty(
                "document-store.retention.permanent-erasure-write-fence-enabled",
                Boolean.class,
                false);
        if (purgeEnabled && !permanentErasureEnabled) {
            throw new IllegalStateException(
                    "Production document purge requires the reviewed permanent-erasure capability");
        }
        if (!permanentErasureEnabled) {
            if (writeFenceEnabled) {
                verifyPermanentErasureWriteFence();
            }
            return;
        }
        if (!writeFenceEnabled) {
            throw new IllegalStateException(
                    "Permanent erasure requires the permanent write fence");
        }
        if (!purgeEnabled) {
            throw new IllegalStateException(
                    "Permanent erasure requires document purge to be explicitly enabled");
        }
        requireTrue(
                "document-store.retention.versioned-object-erasure-enabled",
                "Permanent erasure requires reviewed versioned-object deletion");
        String policyVersion = required("document-store.retention.policy-version");
        if ("UNAPPROVED".equalsIgnoreCase(policyVersion.trim())) {
            throw new IllegalStateException(
                    "Permanent erasure requires an approved retention policy version");
        }
        String backupPolicyVersion = required(
                "document-store.retention.backup-retention-policy-version");
        if ("UNAPPROVED".equalsIgnoreCase(backupPolicyVersion.trim())) {
            throw new IllegalStateException(
                    "Permanent erasure requires an approved backup-retention policy version");
        }
        if (!"task-role".equalsIgnoreCase(required(
                "document-store.object-storage.s3.credentials-provider"))) {
            throw new IllegalStateException(
                    "Permanent erasure requires task-role S3 credentials");
        }
        verifyPermanentErasureWriteFence();
        String retentionAdminToken = required(
                "document-store.security.service-identity.retention-admin-token");
        requireStrongSecret(retentionAdminToken, "retention administrator token");
        String fingerprintKey = required(
                "document-store.retention.erasure-fingerprint-key");
        if (retentionAdminToken.equals(fingerprintKey)) {
            throw new IllegalStateException(
                    "Permanent-erasure fingerprint and administrator credentials must be distinct");
        }
        int backupDays = environment.getProperty(
                "document-store.retention.maximum-backup-retention-days",
                Integer.class,
                0);
        if (backupDays < 1 || backupDays > 35) {
            throw new IllegalStateException(
                    "Permanent-erasure maximum backup retention must be explicitly bounded to 1-35 days");
        }
    }

    private void verifyPermanentErasureWriteFence() {
        String fingerprintKey = required(
                "document-store.retention.erasure-fingerprint-key");
        requireStrongSecret(fingerprintKey, "erasure fingerprint key");
    }

    private void requireStrongSecret(String value, String name) {
        if (value.length() < 32
                || value.length() > 512
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalStateException(
                    "Permanent-erasure " + name + " must be 32-512 non-control characters");
        }
    }

    private void verifyObjectStoreCredentials() {
        String provider = required("document-store.object-storage.s3.credentials-provider");
        String accessKey = environment.getProperty(
                "document-store.object-storage.s3.access-key", "");
        String secretKey = environment.getProperty(
                "document-store.object-storage.s3.secret-key", "");
        if ("task-role".equalsIgnoreCase(provider)) {
            if (!accessKey.isBlank() || !secretKey.isBlank()) {
                throw new IllegalStateException(
                        "Static S3 credentials are forbidden when task-role credentials are selected");
            }
            if (!environment.getProperty(
                            "document-store.object-storage.s3.endpoint", "")
                    .isBlank()) {
                throw new IllegalStateException(
                        "A custom S3 endpoint is forbidden when task-role credentials are selected");
            }
            return;
        }
        if ("static".equalsIgnoreCase(provider)) {
            required("document-store.object-storage.s3.access-key");
            required("document-store.object-storage.s3.secret-key");
            return;
        }
        throw new IllegalStateException(
                "Document Store S3 credentials provider must be task-role or static");
    }

    private void requireTrue(String property, String message) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            throw new IllegalStateException(message + ": " + property);
        }
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required storage setting: " + property);
        }
        return value;
    }
}

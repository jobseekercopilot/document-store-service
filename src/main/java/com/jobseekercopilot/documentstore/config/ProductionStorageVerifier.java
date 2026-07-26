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

        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Reviewed Flyway migrations are required for Document Store");
        }
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

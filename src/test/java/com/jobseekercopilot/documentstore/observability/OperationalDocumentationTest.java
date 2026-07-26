package com.jobseekercopilot.documentstore.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OperationalDocumentationTest {

    private static final Path RUNBOOK =
            Path.of("docs/OBSERVABILITY_AND_OPERATIONS.md");
    private static final Path APPLICATION_CONFIGURATION =
            Path.of("src/main/resources/application.yml");

    @Test
    void runbookContainsTheRequiredBetaOperationalContract()
            throws Exception {
        String runbook = Files.readString(RUNBOOK);
        String configuration = Files.readString(APPLICATION_CONFIGURATION);

        assertThat(runbook)
                .contains(
                        "## Signal catalogue",
                        DocumentStoreMetrics.OPERATION_COUNT,
                        DocumentStoreMetrics.OPERATION_DURATION,
                        DocumentStoreMetrics.PAYLOAD_SIZE,
                        DocumentStoreMetrics.RECONCILIATION_COUNT,
                        DocumentStoreMetrics.ACCESS_DENIED_COUNT,
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "## Logging and privacy rules",
                        "## Dashboard and alert contract",
                        "More than 5% over 5 minutes with at least 10 operations",
                        "## Cost-free synthetic path",
                        "This path must not call an LLM",
                        "## Incident runbook",
                        "### 3. Reconciliation safeguards",
                        "### 4. Backup, restore and recovery",
                        "### 5. Privacy-safe support evidence",
                        "These tests prove repository behavior only");
        assertThat(runbook)
                .doesNotContain(
                        "AKIA",
                        "sk_live_",
                        "Bearer ey",
                        "password=secret");
        assertThat(configuration)
                .contains(
                        "show-sql: false",
                        "open-in-view: false",
                        "enabled: false",
                        "com.zaxxer.hikari: WARN",
                        "org.flywaydb.core: WARN",
                        "org.hibernate.SQL: \"OFF\"",
                        "org.hibernate.orm.jdbc.bind: \"OFF\"",
                        "org.springframework.jdbc.core: INFO",
                        "org.springframework.web: INFO",
                        "org.springframework.web.servlet.handler.HandlerMappingIntrospector: ERROR");
    }
}

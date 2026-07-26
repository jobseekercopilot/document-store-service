package com.jobseekercopilot.documentstore.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "document-store.retention")
public class DocumentRetentionProperties {

    private boolean purgeEnabled;
    private boolean maintenanceEnabled;

    @Min(1)
    @Max(365)
    private int recoveryDays = 30;

    @Min(1)
    @Max(3650)
    private int completedOperationDays = 90;

    @Min(1)
    @Max(3650)
    private int lifecycleAuditDays = 365;

    @Min(1)
    @Max(500)
    private int maintenanceBatchSize = 50;

    @Min(60_000)
    private long maintenanceFixedDelayMs = 86_400_000;

    @NotBlank
    private String policyVersion = "UNAPPROVED";

    public void requireApprovedPurgePolicy() {
        if (!purgeEnabled || !hasApprovedPolicy()) {
            throw new IllegalStateException(
                    "Irreversible document purge is disabled until an approved retention policy is configured.");
        }
    }

    public void requireApprovedMaintenancePolicy() {
        if (!maintenanceEnabled || !hasApprovedPolicy()) {
            throw new IllegalStateException(
                    "Retention maintenance is disabled until an approved retention policy is configured.");
        }
    }

    private boolean hasApprovedPolicy() {
        return policyVersion != null
                && !policyVersion.isBlank()
                && !"UNAPPROVED".equalsIgnoreCase(policyVersion.trim());
    }
}

package com.jobseekercopilot.documentstore.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private boolean permanentErasureEnabled;
    private boolean permanentErasureWriteFenceEnabled;
    private boolean versionedObjectErasureEnabled;
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
    @Max(35)
    private int maximumBackupRetentionDays = 35;

    @Min(1)
    @Max(100)
    private int permanentErasureBatchSize = 10;

    @Min(60_000)
    private long permanentErasureFixedDelayMs = 300_000;

    @Min(1)
    @Max(500)
    private int maintenanceBatchSize = 50;

    @Min(60_000)
    private long maintenanceFixedDelayMs = 86_400_000;

    @NotBlank
    private String policyVersion = "UNAPPROVED";

    @NotBlank
    private String backupRetentionPolicyVersion = "UNAPPROVED";

    @Size(max = 512)
    private String erasureFingerprintKey = "";

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

    public void requireApprovedPermanentErasurePolicy() {
        if (!permanentErasureEnabled
                || !permanentErasureWriteFenceEnabled
                || !purgeEnabled
                || !versionedObjectErasureEnabled
                || !hasApprovedPolicy()
                || !hasApprovedBackupRetentionPolicy()
                || erasureFingerprintKey == null
                || erasureFingerprintKey.length() < 32
                || erasureFingerprintKey.chars()
                        .anyMatch(value -> Character.isISOControl(value))) {
            throw new IllegalStateException(
                    "Permanent account erasure is disabled until the approved retention, backup, object-version and fingerprint controls are configured.");
        }
    }

    private boolean hasApprovedPolicy() {
        return policyVersion != null
                && !policyVersion.isBlank()
                && !"UNAPPROVED".equalsIgnoreCase(policyVersion.trim());
    }

    private boolean hasApprovedBackupRetentionPolicy() {
        return backupRetentionPolicyVersion != null
                && !backupRetentionPolicyVersion.isBlank()
                && !"UNAPPROVED".equalsIgnoreCase(
                        backupRetentionPolicyVersion.trim());
    }
}

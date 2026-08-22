package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PermanentErasureReadinessResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean ready,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        PermanentErasureReadinessStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String policyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String backupRetentionPolicyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int recoveryDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int maximumBackupRetentionDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean backupExpiryEvidenceRequired,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long recoveryJournalWritePending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long recoveryJournalEvidenceMissing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long liveErasureReconciliationPending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long restoreJournalReadPending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long restoreReplayPending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long backupRetentionPending) {
}

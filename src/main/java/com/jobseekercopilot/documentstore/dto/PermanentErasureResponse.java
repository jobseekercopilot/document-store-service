package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PermanentErasureResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentOwnerErasureState status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int documentCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int objectScopeCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int attemptCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean liveDataErased,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean backupRetentionWindowElapsed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean backupExpiryEvidenceRecorded,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean backupCopiesMayRemain,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        OffsetDateTime liveDataErasedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        OffsetDateTime backupRetentionUntil,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        OffsetDateTime completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String policyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String backupRetentionPolicyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int backupRetentionDays) {
}

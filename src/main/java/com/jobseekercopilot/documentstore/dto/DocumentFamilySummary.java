package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Content-free owner-scoped summary of one document family")
public record DocumentFamilySummary(
        UUID documentFamilyId,
        String jobId,
        DocumentType documentType,
        UUID latestDocumentId,
        int latestVersion,
        DocumentSourceType latestSource,
        DocumentLifecycleState latestLifecycle,
        DocumentRetentionState latestRetention,
        UUID currentDocumentId,
        Integer currentVersion,
        long versionCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

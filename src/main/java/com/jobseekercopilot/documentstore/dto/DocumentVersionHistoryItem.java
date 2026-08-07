package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Content-free metadata for one immutable server-numbered document version")
public record DocumentVersionHistoryItem(
        UUID documentId,
        int version,
        String title,
        DocumentSourceType source,
        DocumentLifecycleState lifecycle,
        DocumentRetentionState retention,
        boolean current,
        OffsetDateTime approvedAt,
        OffsetDateTime archivedAt,
        OffsetDateTime deletedAt,
        OffsetDateTime purgeEligibleAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<DocumentArtifactManifestItem> artifacts) {
}

package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Owner-scoped newest-first history for one document family")
public record DocumentFamilyHistoryResponse(
        UUID documentFamilyId,
        String jobId,
        DocumentType documentType,
        UUID currentDocumentId,
        Integer currentVersion,
        List<DocumentVersionHistoryItem> versions) {
}

package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Content-free status for one application-scoped upload operation")
public record ApplicationDocumentUploadResponse(
        UUID operationId,
        String jobId,
        String applicationId,
        DocumentType documentType,
        FileType fileType,
        ApplicationDocumentUploadState state,
        String originalSha256,
        Long originalSize,
        String extractedTextSha256,
        DocumentExtractionState extractionState,
        UUID documentId,
        UUID artifactId,
        String failureCode,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

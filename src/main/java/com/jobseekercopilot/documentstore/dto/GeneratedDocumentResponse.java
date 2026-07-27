package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing generated document details")
public class GeneratedDocumentResponse {

    @Schema(description = "Unique identifier of the document", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "ID of the user who owns the document", example = "user-123")
    private String userId;

    @Schema(description = "ID of the job this document relates to", example = "job-456")
    private String jobId;

    @Schema(description = "ID of the application this document version belongs to")
    private String applicationId;

    @Schema(description = "Stable identifier shared by all versions of this document")
    private UUID documentFamilyId;

    @Schema(description = "Type of document", example = "CV", allowableValues = {"CV", "COVER_LETTER"})
    private DocumentType documentType;

    @Schema(description = "Title of the document", example = "Java Developer CV")
    private String title;

    @Schema(description = "Generated document content", example = "Generated CV content...")
    private String content;

    @Schema(description = "Immutable version number within the document family")
    private Integer version;

    @Schema(description = "Deprecated alias for current", deprecated = true)
    private boolean active;

    @Schema(description = "Whether this approved version is selected for future use")
    private boolean current;

    private DocumentLifecycleState lifecycleState;

    @Schema(description = "Recoverable retention state")
    private DocumentRetentionState retentionState;

    @Schema(description = "SHA-256 of the stored text content")
    private String contentSha256;

    private GenerationMetadata generationMetadata;

    private OffsetDateTime approvedAt;

    private String approvedBy;

    private OffsetDateTime archivedAt;

    private OffsetDateTime deletedAt;

    @Schema(description = "Earliest time an authorized purge may be considered")
    private OffsetDateTime purgeEligibleAt;

    private boolean legalHold;

    @Schema(description = "Original filename for uploaded documents")
    private String originalFilename;

    @Schema(description = "Document source", allowableValues = {"GENERATED", "UPLOADED"})
    private DocumentSourceType sourceType;

    @Schema(description = "User or service that created this document")
    private String createdBy;

    @Schema(description = "Timestamp when the document was created")
    private OffsetDateTime createdAt;

    @Schema(description = "Timestamp when the document was updated")
    private OffsetDateTime updatedAt;
}

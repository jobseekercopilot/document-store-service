package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create a new generated document")
public class CreateDocumentRequest {

    @NotBlank(message = "userId is required")
    @Schema(description = "ID of the user who owns the document", example = "user-123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @NotBlank(message = "jobId is required")
    @Schema(description = "ID of the job this document relates to", example = "job-456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobId;

    @Schema(description = "ID of the application this document version belongs to", example = "550e8400-e29b-41d4-a716-446655440000")
    private String applicationId;

    @Schema(description = "Stable family ID when creating a replacement or regenerated version")
    private java.util.UUID documentFamilyId;

    @NotNull(message = "documentType is required")
    @Schema(description = "Type of document", example = "CV", allowableValues = {"CV", "COVER_LETTER"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private DocumentType documentType;

    @NotBlank(message = "title is required")
    @Schema(description = "Title of the document", example = "Java Developer CV", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank(message = "content is required and cannot be empty")
    @Schema(description = "Generated document content", example = "Generated CV content...", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "Expected next version in the document family", example = "2")
    @Positive(message = "version must be positive")
    private Integer version;

    @Schema(description = "Deprecated compatibility field; drafts cannot be current at creation", deprecated = true)
    private Boolean active;

    @Schema(description = "Original filename for uploaded documents", example = "replacement-cv.docx")
    private String originalFilename;

    @Schema(description = "Document source", example = "UPLOADED", allowableValues = {"GENERATED", "UPLOADED"})
    private DocumentSourceType sourceType;

    @Valid
    @Schema(description = "Required before a generated draft can be approved; omitted for uploads")
    private GenerationMetadata generationMetadata;

    @Valid
    @Schema(
            description = """
                    Immutable profile, evidence-snapshot and validated claim
                    provenance for versioned evidence generation.
                    """)
    private DocumentEvidenceProvenance evidenceProvenance;

    @Schema(description = "User or service that created this document", example = "user-123")
    private String createdBy;
}

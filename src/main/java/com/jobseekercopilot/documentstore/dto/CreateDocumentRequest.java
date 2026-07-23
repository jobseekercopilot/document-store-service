package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "documentType is required")
    @Schema(description = "Type of document", example = "CV", allowableValues = {"CV", "COVER_LETTER"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private DocumentType documentType;

    @NotBlank(message = "title is required")
    @Schema(description = "Title of the document", example = "Java Developer CV", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank(message = "content is required and cannot be empty")
    @Schema(description = "Generated document content", example = "Generated CV content...", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "Version number for this application/document type", example = "2")
    private Integer version;

    @Schema(description = "Whether this version is active", example = "false")
    private Boolean active;

    @Schema(description = "Original filename for uploaded documents", example = "replacement-cv.docx")
    private String originalFilename;

    @Schema(description = "Document source", example = "UPLOADED", allowableValues = {"GENERATED", "UPLOADED"})
    private DocumentSourceType sourceType;

    @Schema(description = "User or service that created this document", example = "user-123")
    private String createdBy;
}

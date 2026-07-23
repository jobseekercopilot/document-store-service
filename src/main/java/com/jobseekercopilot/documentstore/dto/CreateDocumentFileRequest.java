package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.FileType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to store an exported document file")
public class CreateDocumentFileRequest {

    @NotNull(message = "generatedDocumentId is required")
    @Schema(description = "Generated document ID this file belongs to", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID generatedDocumentId;

    @NotNull(message = "fileType is required")
    @Schema(description = "Exported file type", example = "DOCX", allowableValues = {"DOCX", "PDF"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private FileType fileType;

    @NotBlank(message = "fileName is required")
    @Schema(description = "Download filename", example = "cv.docx", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;

    @NotBlank(message = "mimeType is required")
    @Schema(description = "File MIME type", example = "application/vnd.openxmlformats-officedocument.wordprocessingml.document", requiredMode = Schema.RequiredMode.REQUIRED)
    private String mimeType;

    @NotBlank(message = "fileContentBase64 is required")
    @Schema(description = "Base64-encoded file bytes", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileContentBase64;
}

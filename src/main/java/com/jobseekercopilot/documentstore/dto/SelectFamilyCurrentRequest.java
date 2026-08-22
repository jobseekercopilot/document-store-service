package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Concurrency-protected request to move only a document family's current pointer")
public record SelectFamilyCurrentRequest(
        @NotNull UUID documentId,
        @NotNull ExpectedCurrentState expectedCurrentState,
        @Schema(description = "Required only when expectedCurrentState is SELECTED")
        UUID expectedCurrentDocumentId) {
}

package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(description = "Exact immutable evidence revision selected for this document")
public record EvidenceRevisionReference(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID entryId,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID revisionId,
        @Positive
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int revisionNumber,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        EvidenceSection category,
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String contentDigest) {
}

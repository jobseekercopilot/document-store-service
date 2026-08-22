package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RestoreReplayRequest(
        @NotBlank
        @Size(min = 1, max = 256)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 256)
        String evidenceReference) {
}

package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record PermanentErasureRequest(
        @NotNull
        @Size(max = 2000)
        @ArraySchema(minItems = 0, maxItems = 2000, uniqueItems = true)
        List<@NotNull UUID> documentIds,
        @NotBlank @Size(min = 1, max = 128) String approvalReference) {
}

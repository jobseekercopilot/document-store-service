package com.jobseekercopilot.documentstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record PermanentErasureRequest(
        @NotNull @Size(max = 2000) List<@NotNull UUID> documentIds,
        @NotBlank @Size(max = 128) String approvalReference) {
}

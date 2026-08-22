package com.jobseekercopilot.documentstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BackupExpiryAttestationRequest(
        @NotBlank @Size(min = 1, max = 256) String evidenceReference) {
}

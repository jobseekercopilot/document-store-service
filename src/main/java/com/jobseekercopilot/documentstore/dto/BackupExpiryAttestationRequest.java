package com.jobseekercopilot.documentstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BackupExpiryAttestationRequest(
        @NotBlank @Size(max = 256) String evidenceReference) {
}

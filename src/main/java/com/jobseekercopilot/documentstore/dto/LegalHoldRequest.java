package com.jobseekercopilot.documentstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LegalHoldRequest {

    private boolean active;

    @NotBlank
    @Size(max = 128)
    private String reference;
}

package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Non-PII identifiers and hashes for the generation resources used")
public class GenerationMetadata {

    private static final String SEMANTIC_VERSION = "^\\d+\\.\\d+\\.\\d+$";
    private static final String SHA_256 = "^[0-9a-f]{64}$";

    @NotBlank
    private String releaseId;

    @NotBlank
    private String bundleId;

    @NotBlank
    @Pattern(regexp = SEMANTIC_VERSION)
    private String bundleVersion;

    @NotBlank
    @Pattern(regexp = SHA_256)
    private String bundleSha256;

    @NotBlank
    @Pattern(regexp = SEMANTIC_VERSION)
    private String templateVersion;

    @NotBlank
    @Pattern(regexp = SHA_256)
    private String templateSha256;

    @NotBlank
    @Pattern(regexp = SEMANTIC_VERSION)
    private String rulesVersion;

    @NotBlank
    @Pattern(regexp = SHA_256)
    private String rulesSha256;

    @NotBlank
    private String schemaId;

    @NotBlank
    @Pattern(regexp = SEMANTIC_VERSION)
    private String schemaVersion;

    @NotBlank
    @Pattern(regexp = SHA_256)
    private String schemaSha256;

    @NotBlank
    @Pattern(regexp = SEMANTIC_VERSION)
    private String evaluationPolicyVersion;

    @NotBlank
    @Pattern(regexp = SHA_256)
    private String evaluationPolicySha256;
}

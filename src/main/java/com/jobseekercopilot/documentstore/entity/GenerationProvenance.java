package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationProvenance {

    @Column(name = "generation_release_id")
    private String releaseId;

    @Column(name = "generation_bundle_id")
    private String bundleId;

    @Column(name = "generation_bundle_version")
    private String bundleVersion;

    @Column(name = "generation_bundle_sha256", length = 64)
    private String bundleSha256;

    @Column(name = "generation_template_version")
    private String templateVersion;

    @Column(name = "generation_template_sha256", length = 64)
    private String templateSha256;

    @Column(name = "generation_rules_version")
    private String rulesVersion;

    @Column(name = "generation_rules_sha256", length = 64)
    private String rulesSha256;

    @Column(name = "generation_schema_id")
    private String schemaId;

    @Column(name = "generation_schema_version")
    private String schemaVersion;

    @Column(name = "generation_schema_sha256", length = 64)
    private String schemaSha256;

    @Column(name = "generation_evaluation_policy_version")
    private String evaluationPolicyVersion;

    @Column(name = "generation_evaluation_policy_sha256", length = 64)
    private String evaluationPolicySha256;
}

package com.jobseekercopilot.documentstore.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "document-store.reconciliation")
public class DocumentStorageReconciliationProperties {

    private boolean enabled = true;
    private boolean runOnStartup;

    @Min(1)
    @Max(500)
    private int batchSize = 50;

    @Min(0)
    private long minimumAgeSeconds = 300;

    @Min(1_000)
    private long fixedDelayMs = 300_000;

    @Min(0)
    private long initialDelayMs = 60_000;

    @NotBlank
    private String objectPrefix = "documents/";
}

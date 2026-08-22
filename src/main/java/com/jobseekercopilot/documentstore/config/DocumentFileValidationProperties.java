package com.jobseekercopilot.documentstore.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "document-store.validation")
public class DocumentFileValidationProperties {

    @Min(1)
    private long maximumFileBytes = 10L * 1024 * 1024;

    @Min(1)
    private int maximumDocxEntries = 256;

    @Min(1)
    private long maximumDocxEntryBytes = 10L * 1024 * 1024;

    @Min(1)
    private long maximumDocxExpandedBytes = 25L * 1024 * 1024;

    @Min(1)
    private int maximumDocxExpansionRatio = 100;

    @Min(1)
    private int maximumPdfLinkAnnotations = 64;

    @Min(1)
    private int maximumPdfLinkTargetCharacters = 2048;
}

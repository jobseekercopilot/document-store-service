package com.jobseekercopilot.documentstore.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "document-store.upload")
public class DocumentUploadProperties {

    @Min(1)
    private int maximumFamiliesPerOwner = 100;

    @Min(1)
    private int maximumVersionsPerFamily = 20;

    @Min(1)
    private long maximumOriginalBytesPerOwner = 500L * 1024 * 1024;

    @Min(1)
    private int maximumAttemptsPerWindow = 20;

    @Min(1)
    private long rateWindowSeconds = 60;

    @Min(1)
    private int maximumPdfPages = 100;

    @Min(1)
    private int maximumExtractedCharacters = 1_000_000;

    @Min(1)
    private long extractionTimeoutSeconds = 15;

    private boolean cleanupEnabled = true;

    @Min(1)
    private long cleanupMinimumAgeSeconds = 300;

    @Min(1000)
    private long cleanupFixedDelayMs = 300_000;

    @Min(1)
    @Max(500)
    private int cleanupBatchSize = 50;

    @Valid
    private Scanner scanner = new Scanner();

    @Data
    public static class Scanner {
        private String host = "clamav";

        @Min(1)
        @Max(65535)
        private int port = 3310;

        @Min(1)
        private int connectTimeoutMs = 2000;

        @Min(1)
        private int readTimeoutMs = 30_000;

        @Min(1)
        private long maximumSignatureAgeHours = 48;
    }
}

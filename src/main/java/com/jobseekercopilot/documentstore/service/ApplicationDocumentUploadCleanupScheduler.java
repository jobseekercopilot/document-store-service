package com.jobseekercopilot.documentstore.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "document-store.upload.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ApplicationDocumentUploadCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            ApplicationDocumentUploadCleanupScheduler.class);

    private final ApplicationDocumentUploadCleanupService cleanupService;

    @Scheduled(
            initialDelayString =
                    "${document-store.upload.cleanup-fixed-delay-ms:300000}",
            fixedDelayString =
                    "${document-store.upload.cleanup-fixed-delay-ms:300000}")
    public void run() {
        try {
            int cleaned = cleanupService.cleanup();
            log.info("Application document upload cleanup completed count={}", cleaned);
        } catch (RuntimeException exception) {
            log.error("Application document upload cleanup failed");
        }
    }
}

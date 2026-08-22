package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentUploadCleanupService {

    private final ApplicationDocumentUploadRepository repository;
    private final ApplicationDocumentUploadPersistence persistence;
    private final DocumentUploadProperties properties;
    private final DocumentStoreMetrics metrics;

    public int cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(
                properties.getCleanupMinimumAgeSeconds());
        var uploads = repository
                .findByStateInAndUpdatedAtBeforeAndQuarantineKeyIsNotNullOrderByUpdatedAtAsc(
                        EnumSet.allOf(ApplicationDocumentUploadState.class),
                        cutoff,
                        PageRequest.of(0, properties.getCleanupBatchSize()));
        int cleaned = 0;
        for (var upload : uploads) {
            try {
                if (persistence.cleanupQuarantine(
                        upload.getOwnerId(),
                        upload.getId(),
                        upload.getState(),
                        cutoff,
                        true)) {
                    cleaned++;
                }
            } catch (RuntimeException exception) {
                metrics.recordReconciliation(
                        "upload", "failure", "delete_pending");
            }
        }
        if (cleaned > 0) {
            metrics.recordReconciliation(
                    "upload", "repaired", "delete_pending", cleaned);
        }
        return cleaned;
    }
}

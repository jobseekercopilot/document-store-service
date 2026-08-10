package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.time.LocalDateTime;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentUploadCleanupService {

    private final ApplicationDocumentUploadRepository repository;
    private final DocumentObjectStorage objectStorage;
    private final DocumentUploadProperties properties;
    private final DocumentStoreMetrics metrics;

    public int cleanup() {
        var uploads = repository
                .findByStateInAndUpdatedAtBeforeAndQuarantineKeyIsNotNullOrderByUpdatedAtAsc(
                        EnumSet.allOf(ApplicationDocumentUploadState.class),
                        LocalDateTime.now().minusSeconds(
                                properties.getCleanupMinimumAgeSeconds()),
                        PageRequest.of(0, properties.getCleanupBatchSize()));
        int cleaned = 0;
        for (var upload : uploads) {
            try {
                if (!upload.getState().terminal()) {
                    upload.setState(ApplicationDocumentUploadState.FAILED);
                    upload.setFailureCode("PROCESSING_TIMEOUT");
                    upload.setFailureMessage(
                            "Document processing did not complete within its safe recovery window.");
                }
                objectStorage.delete(upload.getQuarantineKey());
                upload.setQuarantineKey(null);
                repository.saveAndFlush(upload);
                cleaned++;
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

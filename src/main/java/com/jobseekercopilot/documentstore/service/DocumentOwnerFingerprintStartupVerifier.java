package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.config.PermanentErasureJournalProperties;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class DocumentOwnerFingerprintStartupVerifier implements ApplicationRunner {

    private final DocumentRetentionProperties properties;
    private final PermanentErasureJournalProperties journalProperties;
    private final DocumentOwnerErasureGuard guard;
    private final DocumentOwnerErasureOperationRepository operationRepository;
    private final DocumentOwnerErasureRestoreRequestRepository restoreRequestRepository;

    @Override
    public void run(ApplicationArguments args) {
        long retainedOperations = operationRepository.count();
        long pendingRestoreRequests = restoreRequestRepository.count();
        if (retainedOperations > 0 || pendingRestoreRequests > 0) {
            if (!properties.isPermanentErasureWriteFenceEnabled()) {
                throw new IllegalStateException(
                        "The permanent-erasure write fence must remain enabled while retained evidence exists.");
            }
            journalProperties.requireConfigured();
        }
        if (properties.isPermanentErasureWriteFenceEnabled()
                || retainedOperations > 0
                || pendingRestoreRequests > 0) {
            guard.requireFingerprintContinuity();
        }
    }
}

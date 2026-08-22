package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentOwnerErasureGuard {

    private final DocumentOperationLock operationLock;
    private final DocumentRetentionProperties properties;
    private final DocumentOwnerFingerprint fingerprint;
    private final DocumentOwnerErasureOperationRepository operationRepository;
    private final DocumentOwnerErasureRestoreRequestRepository restoreRequestRepository;

    public void requireWritable(String ownerId) {
        if (!fingerprint.configured()) {
            if (properties.isPermanentErasureWriteFenceEnabled()
                    || operationRepository.count() > 0
                    || restoreRequestRepository.count() > 0) {
                throw new OperationConflictException(
                        "Document writes are disabled because the permanent-erasure write fence is unavailable.");
            }
            return;
        }
        requireFingerprintContinuity();
        acquire(ownerId);
        var fingerprints = fingerprint.fingerprints(ownerId);
        if (operationRepository.existsByOwnerFingerprintIn(fingerprints)
                || restoreRequestRepository.existsByOwnerFingerprintIn(fingerprints)) {
            throw new OperationConflictException(
                    "Document access has been revoked for permanent account erasure.");
        }
    }

    public void requireFingerprintContinuity() {
        if (!fingerprint.configured()) {
            throw new OperationConflictException(
                    "Permanent erasure is disabled because its write fence is unavailable.");
        }
        var configuredVerifiers = fingerprint.keyVerifiers();
        var retainedVerifiers = operationRepository.findDistinctFingerprintKeyVerifiers();
        var pendingRestoreVerifiers =
                restoreRequestRepository.findDistinctFingerprintKeyVerifiers();
        if (!configuredVerifiers.containsAll(retainedVerifiers)
                || !configuredVerifiers.containsAll(pendingRestoreVerifiers)) {
            throw new OperationConflictException(
                    "Permanent erasure is disabled because its fingerprint key does not match retained evidence.");
        }
    }

    public void acquire(String ownerId) {
        fingerprint.fingerprints(ownerId).stream()
                .sorted()
                .forEach(value -> operationLock.acquire(
                        "document-owner-lifecycle:" + value));
    }

}

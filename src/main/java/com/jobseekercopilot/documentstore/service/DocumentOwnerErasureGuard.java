package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentOwnerErasureGuard {

    private final DocumentOperationLock operationLock;
    private final DocumentRetentionProperties properties;
    private final DocumentOwnerFingerprint fingerprint;
    private final DocumentOwnerErasureOperationRepository operationRepository;

    public void requireWritable(String ownerId) {
        if (!fingerprint.configured()) {
            if (properties.isPermanentErasureWriteFenceEnabled()
                    || operationRepository.count() > 0) {
                throw new OperationConflictException(
                        "Document writes are disabled because the permanent-erasure write fence is unavailable.");
            }
            return;
        }
        if (operationRepository.existsByFingerprintKeyVerifierNot(
                fingerprint.keyVerifier())) {
            throw new OperationConflictException(
                    "Document writes are disabled because the permanent-erasure fingerprint key does not match retained evidence.");
        }
        acquire(ownerId);
        if (operationRepository.existsByOwnerFingerprint(
                fingerprint.fingerprint(ownerId))) {
            throw new OperationConflictException(
                    "Document access has been revoked for permanent account erasure.");
        }
    }

    public void requireFingerprintContinuity() {
        if (!fingerprint.configured()) {
            throw new OperationConflictException(
                    "Permanent erasure is disabled because its write fence is unavailable.");
        }
        if (operationRepository.existsByFingerprintKeyVerifierNot(
                fingerprint.keyVerifier())) {
            throw new OperationConflictException(
                    "Permanent erasure is disabled because its fingerprint key does not match retained evidence.");
        }
    }

    public void acquire(String ownerId) {
        operationLock.acquire("document-owner-lifecycle:"
                + fingerprint.fingerprint(ownerId));
    }

}

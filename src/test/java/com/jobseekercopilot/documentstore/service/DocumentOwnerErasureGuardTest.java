package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import org.junit.jupiter.api.Test;
import java.util.List;

class DocumentOwnerErasureGuardTest {

    @Test
    void retainedEvidenceRejectsAllWritesWhenKeyWasRemoved() {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.count()).thenReturn(1L);
        DocumentOwnerErasureGuard guard = guard(properties, operations);

        assertThatThrownBy(() -> guard.requireWritable("erased-owner"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("write fence is unavailable");
    }

    @Test
    void changedFingerprintKeyRejectsAllWritesInsteadOfMissingTheOwner() {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        properties.setPermanentErasureWriteFenceEnabled(true);
        properties.setErasureFingerprintKey(
                "replacement-fingerprint-secret-key-0001");
        DocumentRetentionProperties retainedProperties =
                new DocumentRetentionProperties();
        retainedProperties.setErasureFingerprintKey(
                "original-fingerprint-secret-key-0000001");
        String retainedVerifier =
                new DocumentOwnerFingerprint(retainedProperties).keyVerifier();
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.findDistinctFingerprintKeyVerifiers())
                .thenReturn(List.of(retainedVerifier));
        DocumentOwnerErasureGuard guard = guard(properties, operations);

        assertThatThrownBy(() -> guard.requireWritable("any-owner"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("does not match retained evidence");
    }

    @Test
    void previousKeyKeepsRetainedEvidenceAndOwnerRevocationActiveDuringRotation() {
        DocumentRetentionProperties retainedProperties =
                new DocumentRetentionProperties();
        retainedProperties.setErasureFingerprintKey(
                "original-fingerprint-secret-key-0000001");
        String retainedVerifier =
                new DocumentOwnerFingerprint(retainedProperties).keyVerifier();

        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        properties.setPermanentErasureWriteFenceEnabled(true);
        properties.setErasureFingerprintKey(
                "replacement-fingerprint-secret-key-0001");
        properties.setErasureFingerprintPreviousKeys(
                "original-fingerprint-secret-key-0000001");
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.findDistinctFingerprintKeyVerifiers())
                .thenReturn(List.of(retainedVerifier));
        when(operations.existsByOwnerFingerprintIn(anyCollection()))
                .thenReturn(true);
        DocumentOwnerErasureGuard guard = guard(properties, operations);

        assertThatThrownBy(() -> guard.requireWritable("erased-owner"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("revoked for permanent account erasure");
        assertThatCode(guard::requireFingerprintContinuity)
                .doesNotThrowAnyException();
    }

    @Test
    void malformedOrDuplicatePreviousKeyRingFailsClosed() {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        properties.setPermanentErasureWriteFenceEnabled(true);
        properties.setErasureFingerprintKey(
                "replacement-fingerprint-secret-key-0001");
        properties.setErasureFingerprintPreviousKeys(
                "original-fingerprint-secret-key-0000001,");
        DocumentOwnerErasureGuard guard = guard(
                properties, mock(DocumentOwnerErasureOperationRepository.class));
        assertThatThrownBy(guard::requireFingerprintContinuity)
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("write fence is unavailable");

        properties.setErasureFingerprintPreviousKeys(
                "replacement-fingerprint-secret-key-0001");
        assertThatThrownBy(guard::requireFingerprintContinuity)
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("write fence is unavailable");
    }

    private DocumentOwnerErasureGuard guard(
            DocumentRetentionProperties properties,
            DocumentOwnerErasureOperationRepository operations) {
        DocumentOwnerFingerprint fingerprint = new DocumentOwnerFingerprint(properties);
        return new DocumentOwnerErasureGuard(
                mock(DocumentOperationLock.class),
                properties,
                fingerprint,
                operations,
                mock(DocumentOwnerErasureRestoreRequestRepository.class));
    }
}

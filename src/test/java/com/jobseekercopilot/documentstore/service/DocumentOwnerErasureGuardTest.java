package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import org.junit.jupiter.api.Test;

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
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.existsByFingerprintKeyVerifierNot(anyString()))
                .thenReturn(true);
        DocumentOwnerErasureGuard guard = guard(properties, operations);

        assertThatThrownBy(() -> guard.requireWritable("any-owner"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("does not match retained evidence");
    }

    private DocumentOwnerErasureGuard guard(
            DocumentRetentionProperties properties,
            DocumentOwnerErasureOperationRepository operations) {
        DocumentOwnerFingerprint fingerprint = new DocumentOwnerFingerprint(properties);
        return new DocumentOwnerErasureGuard(
                mock(DocumentOperationLock.class),
                properties,
                fingerprint,
                operations);
    }
}

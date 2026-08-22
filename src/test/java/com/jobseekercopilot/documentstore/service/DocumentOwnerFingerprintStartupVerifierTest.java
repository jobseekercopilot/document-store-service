package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.config.PermanentErasureJournalProperties;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DocumentOwnerFingerprintStartupVerifierTest {

    @Test
    void startupRejectsRemovingAKeyThatStillProtectsRetainedEvidence() {
        DocumentRetentionProperties current = properties(
                "replacement-fingerprint-secret-key-0001");
        DocumentRetentionProperties retained = properties(
                "original-fingerprint-secret-key-0000001");
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.count()).thenReturn(1L);
        when(operations.findDistinctFingerprintKeyVerifiers())
                .thenReturn(List.of(
                        new DocumentOwnerFingerprint(retained).keyVerifier()));

        var verifier = verifier(current, operations);

        assertThatThrownBy(() -> verifier.run(new DefaultApplicationArguments()))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("does not match retained evidence");
    }

    @Test
    void startupAllowsTheRetentionAdminApiToRepairLegacyJournalEvidence() {
        DocumentRetentionProperties properties = properties(
                "current-fingerprint-secret-key-00000001");
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.count()).thenReturn(1L);
        when(operations.findDistinctFingerprintKeyVerifiers())
                .thenReturn(List.of(
                        new DocumentOwnerFingerprint(properties).keyVerifier()));
        when(operations.countAdvancedOperationsWithoutJournalEvidence())
                .thenReturn(1L);

        var verifier = verifier(properties, operations);

        assertThatCode(() -> verifier.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void startupRejectsDarkeningTheFenceWhileEvidenceRemains() {
        DocumentRetentionProperties properties = properties(
                "current-fingerprint-secret-key-00000001");
        properties.setPermanentErasureWriteFenceEnabled(false);
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        when(operations.count()).thenReturn(1L);

        var verifier = verifier(properties, operations);

        assertThatThrownBy(() -> verifier.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write fence must remain enabled");
    }

    @Test
    void startupRejectsRemovingTheKeyForADurableRestoreRequest() {
        DocumentRetentionProperties current = properties(
                "replacement-fingerprint-secret-key-0001");
        DocumentRetentionProperties retained = properties(
                "original-fingerprint-secret-key-0000001");
        DocumentOwnerErasureOperationRepository operations =
                mock(DocumentOwnerErasureOperationRepository.class);
        DocumentOwnerErasureRestoreRequestRepository restoreRequests =
                mock(DocumentOwnerErasureRestoreRequestRepository.class);
        when(restoreRequests.count()).thenReturn(1L);
        when(restoreRequests.findDistinctFingerprintKeyVerifiers())
                .thenReturn(List.of(
                        new DocumentOwnerFingerprint(retained).keyVerifier()));

        var verifier = verifier(current, operations, restoreRequests);

        assertThatThrownBy(() -> verifier.run(new DefaultApplicationArguments()))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("does not match retained evidence");
    }

    private DocumentOwnerFingerprintStartupVerifier verifier(
            DocumentRetentionProperties properties,
            DocumentOwnerErasureOperationRepository operations) {
        return verifier(
                properties,
                operations,
                mock(DocumentOwnerErasureRestoreRequestRepository.class));
    }

    private DocumentOwnerFingerprintStartupVerifier verifier(
            DocumentRetentionProperties properties,
            DocumentOwnerErasureOperationRepository operations,
            DocumentOwnerErasureRestoreRequestRepository restoreRequests) {
        DocumentOwnerFingerprint fingerprint = new DocumentOwnerFingerprint(properties);
        DocumentOwnerErasureGuard guard = new DocumentOwnerErasureGuard(
                mock(DocumentOperationLock.class),
                properties,
                fingerprint,
                operations,
                restoreRequests);
        return new DocumentOwnerFingerprintStartupVerifier(
                properties,
                journalProperties(),
                guard,
                operations,
                restoreRequests);
    }

    private PermanentErasureJournalProperties journalProperties() {
        PermanentErasureJournalProperties properties =
                new PermanentErasureJournalProperties();
        properties.setProvider(PermanentErasureJournalProperties.Provider.FILESYSTEM);
        properties.setFilesystemRoot(java.nio.file.Path.of("target/test-erasure-journal"));
        return properties;
    }

    private DocumentRetentionProperties properties(String key) {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        properties.setPermanentErasureWriteFenceEnabled(true);
        properties.setErasureFingerprintKey(key);
        return properties;
    }
}

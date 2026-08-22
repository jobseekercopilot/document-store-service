package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.config.PermanentErasureJournalProperties;
import com.jobseekercopilot.documentstore.dto.PermanentErasureReadinessResponse;
import com.jobseekercopilot.documentstore.dto.PermanentErasureReadinessStatus;
import com.jobseekercopilot.documentstore.dto.BackupExpiryAttestationRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureResponse;
import com.jobseekercopilot.documentstore.dto.PermanentErasureStatus;
import com.jobseekercopilot.documentstore.dto.RestoreReplayRequest;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureOperation;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScope;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScopeType;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournal;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournalEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentPermanentErasureService {

    private static final Logger log = LoggerFactory.getLogger(
            DocumentPermanentErasureService.class);
    private static final String SCHEMA = "document-permanent-erasure.v2";

    private final DocumentRetentionProperties properties;
    private final PermanentErasureJournalProperties journalProperties;
    private final DocumentPermanentErasureTransaction transaction;
    private final DocumentOwnerErasureGuard erasureGuard;
    private final DocumentOwnerErasureOperationRepository operationRepository;
    private final DocumentOwnerErasureRestoreRequestRepository restoreRequestRepository;
    private final DocumentObjectStorage objectStorage;
    private final PermanentErasureJournal recoveryJournal;
    private final PermanentErasureRecoveryJournalCodec recoveryJournalCodec;
    private final DocumentStoreMetrics metrics;

    public PermanentErasureResponse startOrResume(
            String ownerId,
            UUID operationId,
            PermanentErasureRequest request,
            String operatorId) {
        transaction.prepare(ownerId, operationId, request, operatorId);
        resume(operationId);
        return response(operationId);
    }

    public PermanentErasureResponse attestBackupExpiry(
            String ownerId,
            UUID operationId,
            BackupExpiryAttestationRequest request,
            String operatorId) {
        transaction.attestBackupExpiry(
                ownerId, operationId, request.evidenceReference(), operatorId);
        resume(operationId);
        return response(operationId);
    }

    public PermanentErasureResponse restoreReplay(
            String ownerId,
            UUID operationId,
            UUID restoreReplayId,
            RestoreReplayRequest request,
            String operatorId) {
        DocumentOwnerErasureOperation alreadyApplied =
                transaction.prepareRestoreReplay(
                        ownerId,
                        operationId,
                        restoreReplayId,
                        request.evidenceReference(),
                        operatorId);
        if (alreadyApplied != null) {
            resume(operationId);
            return response(operationId);
        }
        try {
            resumeRestoreReplayRequest(operationId, restoreReplayId);
            return response(operationId);
        } catch (ResourceNotFoundException unauthorized) {
            transaction.discardUnauthorizedRestoreReplay(
                    operationId, restoreReplayId);
            throw unauthorized;
        }
    }

    private DocumentOwnerErasureOperation resumeRestoreReplayRequest(
            UUID operationId, UUID restoreReplayId) {
        DocumentOwnerErasureOperation retained = operationRepository
                .findById(operationId)
                .orElse(null);
        String boundVersion = retained == null
                ? null
                : retained.getJournalObjectVersion();
        PermanentErasureJournalEntry evidence = recoveryJournal.read(
                operationId, boundVersion);
        PermanentErasureRecoveryJournalRecord record =
                recoveryJournalCodec.decodeCanonical(evidence.canonicalContent());
        requireJournalDigest(evidence);
        transaction.beginRestoreReplay(
                operationId,
                restoreReplayId,
                record,
                evidence);
        return resume(operationId);
    }

    public PermanentErasureResponse status(String ownerId, UUID operationId) {
        transaction.requireOwned(ownerId, operationId);
        return response(operationId);
    }

    public PermanentErasureReadinessResponse readiness() {
        long missingJournalEvidence =
                operationRepository.countAdvancedOperationsWithoutJournalEvidence();
        boolean configured;
        try {
            properties.requireApprovedPermanentErasurePolicy();
            journalProperties.requireConfigured();
            erasureGuard.requireFingerprintContinuity();
            configured = true;
        } catch (IllegalStateException | OperationConflictException exception) {
            configured = false;
        }
        long journalPending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.JOURNAL_PENDING));
        long livePending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING));
        // The transaction moves a restore request forward into the operation state.
        // Reading the source first prevents one in-flight obligation from falling
        // between two independently committed readiness queries.
        long restoreJournalReadPending = restoreRequestRepository.count();
        long restorePending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.RESTORE_REPLAY_PENDING));
        long backupPending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING));
        boolean enabled = properties.isPermanentErasureEnabled();
        boolean ready = configured
                && missingJournalEvidence == 0
                && journalPending == 0
                && livePending == 0
                && restoreJournalReadPending == 0
                && restorePending == 0
                && backupPending == 0;
        PermanentErasureReadinessStatus status = !enabled
                ? PermanentErasureReadinessStatus.DISABLED
                : !configured
                        ? PermanentErasureReadinessStatus.MISCONFIGURED
                        : ready
                                ? PermanentErasureReadinessStatus.READY
                                : PermanentErasureReadinessStatus.RECONCILIATION_REQUIRED;
        return new PermanentErasureReadinessResponse(
                "document-permanent-erasure-readiness.v2",
                enabled,
                ready,
                status,
                configured ? properties.getPolicyVersion().trim() : null,
                configured
                        ? properties.getBackupRetentionPolicyVersion().trim()
                        : null,
                properties.getRecoveryDays(),
                properties.getMaximumBackupRetentionDays(),
                true,
                journalPending,
                missingJournalEvidence,
                livePending,
                restoreJournalReadPending,
                restorePending,
                backupPending);
    }

    public int reconcileBatch() {
        try {
            properties.requireApprovedPermanentErasurePolicy();
            journalProperties.requireConfigured();
            erasureGuard.requireFingerprintContinuity();
        } catch (IllegalStateException disabled) {
            return 0;
        } catch (OperationConflictException disabled) {
            return 0;
        }
        int limit = properties.getPermanentErasureBatchSize();
        var restoreRequests = restoreRequestRepository
                .findAllByOrderByUpdatedAtAscRestoreReplayIdAsc(
                        PageRequest.of(0, limit));
        int completed = 0;
        int failed = 0;
        for (var request : restoreRequests) {
            try {
                transaction.markRestoreReplayAttempt(
                        request.getOperationId(), request.getRestoreReplayId());
                resumeRestoreReplayRequest(
                        request.getOperationId(), request.getRestoreReplayId());
                completed++;
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        if (operationRepository.countAdvancedOperationsWithoutJournalEvidence() > 0) {
            recordReconciliation(restoreRequests.size(), completed, failed);
            return completed;
        }
        int remainingLimit = limit - restoreRequests.size();
        if (remainingLimit == 0) {
            recordReconciliation(restoreRequests.size(), completed, failed);
            return completed;
        }
        List<DocumentOwnerErasureOperation> pending = operationRepository
                .findByStateInOrderByUpdatedAtAscOperationIdAsc(
                        List.of(
                                DocumentOwnerErasureState.JOURNAL_PENDING,
                                DocumentOwnerErasureState.OBJECT_ERASURE_PENDING,
                                DocumentOwnerErasureState.RESTORE_REPLAY_PENDING),
                        PageRequest.of(0, remainingLimit));
        int remaining = remainingLimit - pending.size();
        if (remaining > 0) {
            pending = new java.util.ArrayList<>(pending);
            pending.addAll(operationRepository
                    .findDueAttestedBackupRetention(
                            DocumentOwnerErasureState.BACKUP_RETENTION_PENDING,
                            LocalDateTime.now(ZoneOffset.UTC),
                            PageRequest.of(0, remaining)));
        }
        for (DocumentOwnerErasureOperation operation : pending) {
            try {
                DocumentOwnerErasureState before = operation.getState();
                DocumentOwnerErasureOperation after = resume(
                        operation.getOperationId());
                if (after.getState() != before) {
                    completed++;
                }
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        recordReconciliation(
                restoreRequests.size() + pending.size(), completed, failed);
        return completed;
    }

    private void recordReconciliation(int processed, int completed, int failed) {
        metrics.recordReconciliation(
                "document", "repaired", "delete_pending", completed);
        metrics.recordReconciliation(
                "document", "failure", "delete_pending", failed);
        if (processed > 0) {
            log.info(
                    "Permanent-erasure reconciliation completed processed={} progressed={} failed={}",
                    processed,
                    completed,
                    failed);
        }
    }

    private DocumentOwnerErasureOperation resume(UUID operationId) {
        DocumentOwnerErasureOperation operation = transaction.markAttempt(operationId);
        if (operation.getState() == DocumentOwnerErasureState.COMPLETED) {
            return operation;
        }
        if (operation.getState() == DocumentOwnerErasureState.JOURNAL_PENDING) {
            operation = persistRecoveryJournal(operationId);
        }
        if (operation.getState() == DocumentOwnerErasureState.BACKUP_RETENTION_PENDING
                && (!backupWindowElapsed(operation)
                        || operation.getBackupExpiryEvidenceSha256() == null)) {
            return operation;
        }

        eraseScopes(operationId, transaction.scopes(operationId));
        if (operation.getState() == DocumentOwnerErasureState.OBJECT_ERASURE_PENDING) {
            return transaction.eraseLiveDatabase(operationId);
        }
        if (operation.getState() == DocumentOwnerErasureState.RESTORE_REPLAY_PENDING) {
            return transaction.eraseRestoredDatabase(operationId);
        }
        return transaction.completeBackupRetention(operationId);
    }

    private DocumentOwnerErasureOperation persistRecoveryJournal(UUID operationId) {
        PermanentErasureRecoveryJournalRecord record =
                transaction.recoveryJournalRecord(operationId);
        PermanentErasureRecoveryJournalCodec.Encoded encoded =
                recoveryJournalCodec.encode(record);
        PermanentErasureJournalEntry evidence = recoveryJournal.writeOrVerify(
                operationId, encoded.content(), encoded.sha256());
        PermanentErasureRecoveryJournalRecord persisted =
                recoveryJournalCodec.decodeCanonical(evidence.canonicalContent());
        requireJournalDigest(evidence);
        if (!record.equals(persisted)
                || !MessageDigest.isEqual(
                        encoded.sha256().getBytes(StandardCharsets.US_ASCII),
                        evidence.contentSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new OperationConflictException(
                    "Permanent-erasure recovery journal does not match the durable operation.");
        }
        return transaction.bindRecoveryJournal(
                operationId, encoded.sha256(), evidence);
    }

    private void requireJournalDigest(PermanentErasureJournalEntry evidence) {
        String calculated = recoveryJournalCodec.sha256(evidence.canonicalContent());
        if (!MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.US_ASCII),
                evidence.contentSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new OperationConflictException(
                    "Permanent-erasure recovery journal digest is invalid.");
        }
    }

    private void eraseScopes(
            UUID operationId, List<DocumentOwnerErasureScope> scopes) {
        for (DocumentOwnerErasureScope scope : scopes) {
            if (scope.getScopeType()
                    == DocumentOwnerErasureScopeType.DOCUMENT_PREFIX) {
                objectStorage.permanentlyDeletePrefix(scope.getStorageScope());
            } else {
                objectStorage.permanentlyDeleteKey(scope.getStorageScope());
            }
            transaction.markScopeErased(
                    operationId,
                    scope.getId(),
                    LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    private PermanentErasureResponse response(UUID operationId) {
        var snapshot = transaction.statusSnapshot(operationId);
        var operation = snapshot.operation();
        var restoreRequest = snapshot.restoreRequest();
        if (restoreRequest != null) {
            return new PermanentErasureResponse(
                    SCHEMA,
                    operation.getOperationId(),
                    PermanentErasureStatus.RESTORE_JOURNAL_READ_PENDING,
                    operation.getDocumentCount(),
                    operation.getObjectScopeCount(),
                    operation.getAttemptCount(),
                    operation.getJournalContentSha256() != null,
                    false,
                    false,
                    false,
                    true,
                    null,
                    null,
                    null,
                    restoreRequest.getRestoreReplayId(),
                    true,
                    utc(restoreRequest.getRequestedAt()),
                    null,
                    operation.getPolicyVersion(),
                    operation.getBackupRetentionPolicyVersion(),
                    operation.getBackupRetentionDays());
        }
        return new PermanentErasureResponse(
                SCHEMA,
                operation.getOperationId(),
                PermanentErasureStatus.valueOf(operation.getState().name()),
                operation.getDocumentCount(),
                operation.getObjectScopeCount(),
                operation.getAttemptCount(),
                operation.getJournalContentSha256() != null,
                operation.getLiveDataErasedAt() != null,
                backupWindowElapsed(operation),
                operation.getBackupExpiryEvidenceSha256() != null,
                operation.getState() != DocumentOwnerErasureState.COMPLETED,
                utc(operation.getLiveDataErasedAt()),
                utc(operation.getBackupRetentionUntil()),
                utc(operation.getCompletedAt()),
                operation.getRestoreReplayId(),
                operation.getRestoreReplayEvidenceSha256() != null,
                utc(operation.getRestoreReplayRequestedAt()),
                utc(operation.getRestoreReplayObjectErasedAt()),
                operation.getPolicyVersion(),
                operation.getBackupRetentionPolicyVersion(),
                operation.getBackupRetentionDays());
    }

    private boolean backupWindowElapsed(DocumentOwnerErasureOperation operation) {
        return operation.getBackupRetentionUntil() != null
                && !LocalDateTime.now(ZoneOffset.UTC).isBefore(
                        operation.getBackupRetentionUntil());
    }

    private OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

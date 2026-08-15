package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.dto.PermanentErasureReadinessResponse;
import com.jobseekercopilot.documentstore.dto.PermanentErasureReadinessStatus;
import com.jobseekercopilot.documentstore.dto.BackupExpiryAttestationRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureResponse;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureOperation;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScope;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScopeType;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
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
    private static final String SCHEMA = "document-permanent-erasure.v1";

    private final DocumentRetentionProperties properties;
    private final DocumentPermanentErasureTransaction transaction;
    private final DocumentOwnerErasureOperationRepository operationRepository;
    private final DocumentObjectStorage objectStorage;
    private final DocumentStoreMetrics metrics;

    public PermanentErasureResponse startOrResume(
            String ownerId,
            UUID operationId,
            PermanentErasureRequest request,
            String operatorId) {
        transaction.prepare(ownerId, operationId, request, operatorId);
        return response(resume(operationId, true));
    }

    public PermanentErasureResponse attestBackupExpiry(
            String ownerId,
            UUID operationId,
            BackupExpiryAttestationRequest request,
            String operatorId) {
        transaction.attestBackupExpiry(
                ownerId, operationId, request.evidenceReference(), operatorId);
        return response(resume(operationId, true));
    }

    public PermanentErasureResponse status(String ownerId, UUID operationId) {
        return response(transaction.requireOwned(ownerId, operationId));
    }

    public PermanentErasureReadinessResponse readiness() {
        boolean configured;
        try {
            properties.requireApprovedPermanentErasurePolicy();
            configured = true;
        } catch (IllegalStateException exception) {
            configured = false;
        }
        long livePending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING));
        long backupPending = operationRepository.countByStateIn(
                List.of(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING));
        boolean enabled = properties.isPermanentErasureEnabled();
        boolean ready = configured && livePending == 0 && backupPending == 0;
        PermanentErasureReadinessStatus status = !enabled
                ? PermanentErasureReadinessStatus.DISABLED
                : !configured
                        ? PermanentErasureReadinessStatus.MISCONFIGURED
                        : ready
                                ? PermanentErasureReadinessStatus.READY
                                : PermanentErasureReadinessStatus.RECONCILIATION_REQUIRED;
        return new PermanentErasureReadinessResponse(
                "document-permanent-erasure-readiness.v1",
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
                livePending,
                backupPending);
    }

    public int reconcileBatch() {
        try {
            properties.requireApprovedPermanentErasurePolicy();
        } catch (IllegalStateException disabled) {
            return 0;
        }
        int limit = properties.getPermanentErasureBatchSize();
        List<DocumentOwnerErasureOperation> pending = operationRepository
                .findByStateInOrderByUpdatedAtAscOperationIdAsc(
                        List.of(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING),
                        PageRequest.of(0, limit));
        int remaining = limit - pending.size();
        if (remaining > 0) {
            pending = new java.util.ArrayList<>(pending);
            pending.addAll(operationRepository
                    .findDueAttestedBackupRetention(
                            DocumentOwnerErasureState.BACKUP_RETENTION_PENDING,
                            LocalDateTime.now(),
                            PageRequest.of(0, remaining)));
        }
        int completed = 0;
        int failed = 0;
        for (DocumentOwnerErasureOperation operation : pending) {
            try {
                DocumentOwnerErasureState before = operation.getState();
                DocumentOwnerErasureOperation after = resume(
                        operation.getOperationId(), false);
                if (after.getState() != before) {
                    completed++;
                }
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        metrics.recordReconciliation(
                "document", "repaired", "delete_pending", completed);
        metrics.recordReconciliation(
                "document", "failure", "delete_pending", failed);
        if (!pending.isEmpty()) {
            log.info(
                    "Permanent-erasure reconciliation completed processed={} progressed={} failed={}",
                    pending.size(),
                    completed,
                    failed);
        }
        return completed;
    }

    private DocumentOwnerErasureOperation resume(
            UUID operationId, boolean explicitReplay) {
        DocumentOwnerErasureOperation operation = transaction.markAttempt(operationId);
        if (explicitReplay) {
            eraseScopes(operationId, transaction.scopes(operationId));
        }
        if (operation.getState() == DocumentOwnerErasureState.COMPLETED) {
            return operation;
        }
        if (operation.getState() == DocumentOwnerErasureState.BACKUP_RETENTION_PENDING
                && (!backupWindowElapsed(operation)
                        || operation.getBackupExpiryEvidenceSha256() == null)) {
            return operation;
        }

        if (!explicitReplay) {
            eraseScopes(operationId, transaction.scopes(operationId));
        }
        if (operation.getState() == DocumentOwnerErasureState.OBJECT_ERASURE_PENDING) {
            return transaction.eraseLiveDatabase(operationId);
        }
        return transaction.completeBackupRetention(operationId);
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
                    operationId, scope.getId(), LocalDateTime.now());
        }
    }

    private PermanentErasureResponse response(DocumentOwnerErasureOperation operation) {
        return new PermanentErasureResponse(
                SCHEMA,
                operation.getOperationId(),
                operation.getState(),
                operation.getDocumentCount(),
                operation.getObjectScopeCount(),
                operation.getAttemptCount(),
                operation.getLiveDataErasedAt() != null,
                backupWindowElapsed(operation),
                operation.getBackupExpiryEvidenceSha256() != null,
                operation.getState() != DocumentOwnerErasureState.COMPLETED,
                utc(operation.getLiveDataErasedAt()),
                utc(operation.getBackupRetentionUntil()),
                utc(operation.getCompletedAt()),
                operation.getPolicyVersion(),
                operation.getBackupRetentionPolicyVersion(),
                operation.getBackupRetentionDays());
    }

    private boolean backupWindowElapsed(DocumentOwnerErasureOperation operation) {
        return operation.getBackupRetentionUntil() != null
                && !LocalDateTime.now().isBefore(
                        operation.getBackupRetentionUntil());
    }

    private OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.config.PermanentErasureJournalProperties;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureOperation;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureRestoreRequest;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScope;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScopeType;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentApplicationWorkflowCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentCurrentCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureScopeRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentTombstoneAssociationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournalEntry;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournalKeys;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentPermanentErasureTransaction {

    private final DocumentRetentionProperties properties;
    private final PermanentErasureJournalProperties journalProperties;
    private final EntityManager entityManager;
    private final DocumentOwnerFingerprint ownerFingerprint;
    private final DocumentOwnerErasureGuard ownerGuard;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureOperationRepository operationRepository;
    private final DocumentOwnerErasureRestoreRequestRepository restoreRequestRepository;
    private final DocumentOwnerErasureScopeRepository scopeRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentStorageOperationRepository storageOperationRepository;
    private final ApplicationDocumentUploadRepository uploadRepository;
    private final DocumentLifecycleEventRepository lifecycleEventRepository;
    private final DocumentActivityEventRepository activityEventRepository;
    private final DocumentApplicationWorkflowCommandRepository workflowRepository;
    private final DocumentCurrentCommandRepository currentCommandRepository;
    private final DocumentTombstoneAssociationRepository tombstoneRepository;

    @Transactional
    public DocumentOwnerErasureOperation prepare(
            String ownerId,
            UUID operationId,
            PermanentErasureRequest request,
            String operatorId) {
        requirePolicy();
        ownerGuard.requireFingerprintContinuity();
        if (operationRepository.countAdvancedOperationsWithoutJournalEvidence() > 0
                || restoreRequestRepository.count() > 0) {
            throw new OperationConflictException(
                    "Permanent erasure is blocked until retained recovery-journal evidence is repaired.");
        }
        String normalizedOwner = normalizeOwner(ownerId);
        String fingerprint = ownerFingerprint.fingerprint(normalizedOwner);
        List<String> fingerprints = ownerFingerprint.fingerprints(normalizedOwner);
        List<UUID> requestedIds = normalizeDocumentIds(request.documentIds());
        String approvalSha256 = approvalSha256(request.approvalReference());
        String normalizedOperator = normalizeOperator(operatorId);
        String requestSha256 = OperationFingerprint.sha256(
                requestedIds, approvalSha256);

        ownerGuard.acquire(normalizedOwner);
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        DocumentOwnerErasureOperation existing = operationRepository
                .lockByOperationId(operationId)
                .orElse(null);
        if (existing != null) {
            requireReplay(existing, fingerprints, requestSha256);
            return existing;
        }
        if (operationRepository.findByOwnerFingerprintIn(fingerprints).isPresent()) {
            throw new OperationConflictException(
                    "A permanent-erasure operation already exists for this owner.");
        }

        List<GeneratedDocument> documents = validateOwnerState(
                normalizedOwner, requestedIds);
        List<ApplicationDocumentUpload> uploads = validateUploads(
                normalizedOwner, Set.copyOf(requestedIds));
        LocalDateTime now = utcNow();
        DocumentOwnerErasureOperation operation = operationRepository.saveAndFlush(
                DocumentOwnerErasureOperation.builder()
                        .operationId(operationId)
                        .ownerId(normalizedOwner)
                        .ownerFingerprint(fingerprint)
                        .fingerprintKeyVerifier(ownerFingerprint.keyVerifier())
                        .requestSha256(requestSha256)
                        .approvalReferenceSha256(approvalSha256)
                        .operatorId(normalizedOperator)
                        .state(DocumentOwnerErasureState.JOURNAL_PENDING)
                        .documentCount(documents.size())
                        .objectScopeCount(0)
                        .attemptCount(0)
                        .policyVersion(properties.getPolicyVersion().trim())
                        .backupRetentionPolicyVersion(properties
                                .getBackupRetentionPolicyVersion()
                                .trim())
                        .backupRetentionDays(
                                properties.getMaximumBackupRetentionDays())
                        .journalRequired(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        List<DocumentOwnerErasureScope> scopes = scopes(operationId, requestedIds, uploads);
        if (scopes.size() > 10_000) {
            throw new OperationConflictException(
                    "Permanent erasure exceeds the bounded recovery-journal scope.");
        }
        scopeRepository.saveAllAndFlush(scopes);
        operation.setObjectScopeCount(scopes.size());
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional(readOnly = true)
    public DocumentOwnerErasureOperation requireOwned(
            String ownerId, UUID operationId) {
        ownerGuard.requireFingerprintContinuity();
        DocumentOwnerErasureOperation operation = operationRepository
                .findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Permanent-erasure operation not found."));
        List<String> supplied = ownerFingerprint.fingerprints(
                normalizeOwner(ownerId));
        if (supplied.stream().noneMatch(value ->
                constantTimeEquals(operation.getOwnerFingerprint(), value))) {
            throw new ResourceNotFoundException(
                    "Permanent-erasure operation not found.");
        }
        return operation;
    }

    @Transactional(readOnly = true)
    public DocumentOwnerErasureOperation find(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Permanent-erasure operation not found."));
    }

    @Transactional(readOnly = true)
    public List<DocumentOwnerErasureScope> scopes(UUID operationId) {
        return scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId);
    }

    @Transactional(readOnly = true)
    public PermanentErasureRecoveryJournalRecord recoveryJournalRecord(
            UUID operationId) {
        DocumentOwnerErasureOperation operation = find(operationId);
        if (operation.getState() != DocumentOwnerErasureState.JOURNAL_PENDING
                || operation.getOwnerId() == null
                || !operation.isJournalRequired()) {
            throw new IllegalStateException(
                    "Permanent-erasure recovery journal is not pending.");
        }
        List<DocumentOwnerErasureScope> scopes = scopes(operationId);
        List<UUID> documentIds = scopes.stream()
                .filter(scope -> scope.getScopeType()
                        == DocumentOwnerErasureScopeType.DOCUMENT_PREFIX)
                .map(DocumentOwnerErasureScope::getDocumentId)
                .sorted()
                .toList();
        return new PermanentErasureRecoveryJournalRecord(
                PermanentErasureRecoveryJournalRecord.SCHEMA_VERSION,
                operation.getOperationId(),
                operation.getOwnerId(),
                documentIds,
                scopes.stream()
                        .map(scope -> new PermanentErasureRecoveryJournalRecord.Scope(
                                scope.getScopeType(),
                                scope.getDocumentId(),
                                scope.getStorageScope()))
                        .toList(),
                operation.getRequestSha256(),
                operation.getApprovalReferenceSha256(),
                operation.getPolicyVersion(),
                operation.getBackupRetentionPolicyVersion(),
                operation.getBackupRetentionDays(),
                operation.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    @Transactional
    public DocumentOwnerErasureOperation bindRecoveryJournal(
            UUID operationId,
            String expectedContentSha256,
            PermanentErasureJournalEntry evidence) {
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() != DocumentOwnerErasureState.JOURNAL_PENDING) {
            if (constantTimeEquals(
                            operation.getJournalContentSha256(),
                            expectedContentSha256)
                    && constantTimeEquals(
                            operation.getJournalContentSha256(),
                            evidence.contentSha256())) {
                return operation;
            }
            throw new OperationConflictException(
                    "Permanent-erasure recovery journal evidence conflicts with durable state.");
        }
        String expectedKey = PermanentErasureJournalKeys.forOperation(operationId);
        if (!expectedKey.equals(evidence.objectKey())
                || evidence.objectVersion() == null
                || evidence.objectVersion().isBlank()
                || evidence.objectVersion().length() > 256
                || !constantTimeEquals(expectedContentSha256, evidence.contentSha256())) {
            throw new OperationConflictException(
                    "Permanent-erasure recovery journal evidence is invalid.");
        }
        operation.setJournalSchemaVersion(
                PermanentErasureRecoveryJournalRecord.SCHEMA_VERSION);
        operation.setJournalObjectKey(evidence.objectKey());
        operation.setJournalObjectVersion(evidence.objectVersion());
        operation.setJournalContentSha256(evidence.contentSha256());
        operation.setJournalRecordedAt(utcNow());
        operation.setState(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING);
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional
    public DocumentOwnerErasureOperation prepareRestoreReplay(
            String ownerId,
            UUID operationId,
            UUID restoreReplayId,
            String evidenceReference,
            String operatorId) {
        requirePolicy();
        ownerGuard.requireFingerprintContinuity();
        String normalizedOwner = normalizeOwner(ownerId);
        if (operationId == null || restoreReplayId == null) {
            throw new IllegalArgumentException(
                    "Restore replay operation and replay identifiers are required.");
        }
        String replayEvidenceSha256 = restoreEvidenceSha256(evidenceReference);
        String normalizedOperator = normalizeOperator(operatorId);
        List<String> fingerprints = ownerFingerprint.fingerprints(normalizedOwner);

        ownerGuard.acquire(normalizedOwner);
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        DocumentOwnerErasureOperation operation = operationRepository
                .lockByOperationId(operationId)
                .orElse(null);
        if (operation != null) {
            requireOwnerFingerprint(operation, fingerprints);
            if (restoreReplayId.equals(operation.getRestoreReplayId())) {
                if (!constantTimeEquals(
                        operation.getRestoreReplayEvidenceSha256(),
                        replayEvidenceSha256)) {
                    throw new OperationConflictException(
                            "Restore replay was reused with different evidence.");
                }
                return operation;
            }
            boolean legacyRecovery = !operation.isJournalRequired()
                    && operation.getJournalContentSha256() == null;
            if (!legacyRecovery
                    && operation.getState() != DocumentOwnerErasureState.COMPLETED) {
                throw new OperationConflictException(
                        "A permanent-erasure reconciliation is already pending.");
            }
        }

        DocumentOwnerErasureRestoreRequest existing = restoreRequestRepository
                .lockByOperationId(operationId)
                .orElse(null);
        if (existing != null) {
            if (!restoreReplayId.equals(existing.getRestoreReplayId())
                    || fingerprints.stream().noneMatch(value -> constantTimeEquals(
                            existing.getOwnerFingerprint(), value))
                    || !constantTimeEquals(
                            existing.getEvidenceSha256(), replayEvidenceSha256)) {
                throw new OperationConflictException(
                        "A different restore replay is already pending for this operation.");
            }
            return null;
        }

        LocalDateTime now = utcNow();
        restoreRequestRepository.saveAndFlush(
                DocumentOwnerErasureRestoreRequest.builder()
                        .restoreReplayId(restoreReplayId)
                        .operationId(operationId)
                        .ownerFingerprint(ownerFingerprint.fingerprint(normalizedOwner))
                        .fingerprintKeyVerifier(ownerFingerprint.keyVerifier())
                        .evidenceSha256(replayEvidenceSha256)
                        .requestedBy(normalizedOperator)
                        .requestedAt(now)
                        .updatedAt(now)
                        .build());
        return null;
    }

    @Transactional
    public DocumentOwnerErasureOperation beginRestoreReplay(
            UUID operationId,
            UUID restoreReplayId,
            PermanentErasureRecoveryJournalRecord record,
            PermanentErasureJournalEntry journalEvidence) {
        requirePolicy();
        ownerGuard.requireFingerprintContinuity();
        if (operationId == null
                || restoreReplayId == null
                || record == null
                || !operationId.equals(record.operationId())) {
            throw new OperationConflictException(
                    "Restore replay does not match the immutable recovery journal.");
        }
        String normalizedOwner = normalizeOwner(record.ownerId());
        List<String> fingerprints = ownerFingerprint.fingerprints(normalizedOwner);
        requireCurrentBackupPolicy(record);

        // Keep the same lock order as request creation: owner, operation, durable rows.
        // This prevents an API/scheduler race from deadlocking while still serialising
        // older-database reconstruction with every write for the restored owner.
        ownerGuard.acquire(normalizedOwner);
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        DocumentOwnerErasureOperation operation = operationRepository
                .lockByOperationId(operationId)
                .orElse(null);
        DocumentOwnerErasureRestoreRequest restoreRequest =
                restoreRequestRepository.lockByOperationId(operationId)
                        .orElse(null);
        if (restoreRequest == null) {
            if (operation != null
                    && restoreReplayId.equals(operation.getRestoreReplayId())) {
                requireOwnerFingerprint(operation, fingerprints);
                requireJournalMatchesOperation(operation, record, journalEvidence);
                return operation;
            }
            throw new ResourceNotFoundException(
                    "Restore-replay obligation not found.");
        }
        if (!restoreReplayId.equals(restoreRequest.getRestoreReplayId())) {
            throw new OperationConflictException(
                    "Restore replay does not match the immutable recovery journal.");
        }
        if (fingerprints.stream().noneMatch(value -> constantTimeEquals(
                restoreRequest.getOwnerFingerprint(), value))) {
            throw new ResourceNotFoundException(
                    "Permanent-erasure operation not found.");
        }
        String replayEvidenceSha256 = restoreRequest.getEvidenceSha256();
        String normalizedOperator = restoreRequest.getRequestedBy();

        if (operation != null) {
            requireOwnerFingerprint(operation, fingerprints);
            boolean legacyRecovery = !operation.isJournalRequired()
                    && operation.getJournalContentSha256() == null;
            if (legacyRecovery) {
                requireLegacyOperationMatchesJournal(
                        operation, record, journalEvidence);
            } else {
                requireJournalMatchesOperation(
                        operation, record, journalEvidence);
            }
            if (restoreReplayId.equals(operation.getRestoreReplayId())) {
                if (!constantTimeEquals(
                        operation.getRestoreReplayEvidenceSha256(),
                        replayEvidenceSha256)) {
                    throw new OperationConflictException(
                            "Restore replay was reused with different evidence.");
                }
                restoreRequestRepository.delete(restoreRequest);
                restoreRequestRepository.flush();
                return operation;
            }
            if (!legacyRecovery
                    && operation.getState() != DocumentOwnerErasureState.COMPLETED) {
                throw new OperationConflictException(
                        "A permanent-erasure reconciliation is already pending.");
            }
            requireRestoredOwnerWithinJournal(normalizedOwner, record);
            resetScopesForRestore(operationId);
            if (legacyRecovery) {
                bindRecoveredJournal(operation, record, journalEvidence);
            }
        } else {
            if (operationRepository.findByOwnerFingerprintIn(fingerprints).isPresent()) {
                throw new OperationConflictException(
                        "A permanent-erasure operation already exists for this owner.");
            }
            requireRestoredOwnerWithinJournal(normalizedOwner, record);
            LocalDateTime now = utcNow();
            operation = operationRepository.saveAndFlush(
                    DocumentOwnerErasureOperation.builder()
                            .operationId(operationId)
                            .ownerId(normalizedOwner)
                            .ownerFingerprint(ownerFingerprint.fingerprint(normalizedOwner))
                            .fingerprintKeyVerifier(ownerFingerprint.keyVerifier())
                            .requestSha256(record.requestSha256())
                            .approvalReferenceSha256(
                                    record.approvalReferenceSha256())
                            .operatorId(normalizedOperator)
                            .state(DocumentOwnerErasureState.RESTORE_REPLAY_PENDING)
                            .documentCount(record.documentIds().size())
                            .objectScopeCount(record.objectScopes().size())
                            .attemptCount(0)
                            .policyVersion(record.policyVersion())
                            .backupRetentionPolicyVersion(
                                    record.backupRetentionPolicyVersion())
                            .backupRetentionDays(record.backupRetentionDays())
                            .journalRequired(true)
                            .journalSchemaVersion(record.schemaVersion())
                            .journalObjectKey(journalEvidence.objectKey())
                            .journalObjectVersion(journalEvidence.objectVersion())
                            .journalContentSha256(journalEvidence.contentSha256())
                            .journalRecordedAt(now)
                            .restoreReplayId(restoreReplayId)
                            .restoreReplayEvidenceSha256(replayEvidenceSha256)
                            .restoreReplayRequestedBy(normalizedOperator)
                            .restoreReplayRequestedAt(now)
                            .createdAt(record.createdAt().toLocalDateTime())
                            .updatedAt(now)
                            .build());
            scopeRepository.saveAllAndFlush(record.objectScopes().stream()
                    .map(scope -> DocumentOwnerErasureScope.builder()
                            .id(UUID.randomUUID())
                            .operationId(operationId)
                            .scopeType(scope.scopeType())
                            .documentId(scope.documentId())
                            .storageScope(scope.storageScope())
                            .build())
                    .toList());
        }

        LocalDateTime now = utcNow();
        operation.setOwnerId(normalizedOwner);
        operation.setState(DocumentOwnerErasureState.RESTORE_REPLAY_PENDING);
        operation.setRestoreReplayId(restoreReplayId);
        operation.setRestoreReplayEvidenceSha256(replayEvidenceSha256);
        operation.setRestoreReplayRequestedBy(normalizedOperator);
        operation.setRestoreReplayRequestedAt(now);
        operation.setRestoreReplayObjectErasedAt(null);
        operation.setLiveDataErasedAt(null);
        operation.setBackupRetentionUntil(null);
        operation.setBackupExpiryEvidenceSha256(null);
        operation.setBackupExpiryAttestedBy(null);
        operation.setBackupExpiryAttestedAt(null);
        operation.setCompletedAt(null);
        DocumentOwnerErasureOperation saved =
                operationRepository.saveAndFlush(operation);
        restoreRequestRepository.delete(restoreRequest);
        restoreRequestRepository.flush();
        return saved;
    }

    @Transactional
    public void discardUnauthorizedRestoreReplay(
            UUID operationId, UUID restoreReplayId) {
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        restoreRequestRepository.lockByOperationId(operationId)
                .filter(request -> restoreReplayId.equals(request.getRestoreReplayId()))
                .ifPresent(restoreRequestRepository::delete);
        restoreRequestRepository.flush();
    }

    @Transactional
    public void markRestoreReplayAttempt(
            UUID operationId, UUID restoreReplayId) {
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        restoreRequestRepository.lockByOperationId(operationId)
                .filter(request -> restoreReplayId.equals(
                        request.getRestoreReplayId()))
                .ifPresent(request -> {
                    request.setUpdatedAt(utcNow());
                    restoreRequestRepository.saveAndFlush(request);
                });
    }

    @Transactional
    public DocumentOwnerErasureOperation markAttempt(UUID operationId) {
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() != DocumentOwnerErasureState.COMPLETED) {
            operation.setAttemptCount(operation.getAttemptCount() + 1);
            operation.setLastAttemptAt(utcNow());
            operationRepository.saveAndFlush(operation);
        }
        return operation;
    }

    @Transactional
    public StatusSnapshot statusSnapshot(UUID operationId) {
        operationLock.acquire(
                "document-permanent-erasure-operation:" + operationId);
        DocumentOwnerErasureOperation operation = lock(operationId);
        DocumentOwnerErasureRestoreRequest restoreRequest =
                restoreRequestRepository.lockByOperationId(operationId)
                        .orElse(null);
        return new StatusSnapshot(operation, restoreRequest);
    }

    @Transactional
    public void markScopeErased(
            UUID operationId, UUID scopeId, LocalDateTime erasedAt) {
        DocumentOwnerErasureScope scope = scopeRepository
                .findByIdAndOperationId(scopeId, operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Permanent-erasure object scope is missing."));
        scope.setErasedAt(erasedAt);
        scopeRepository.saveAndFlush(scope);
    }

    @Transactional
    public DocumentOwnerErasureOperation eraseLiveDatabase(UUID operationId) {
        DocumentOwnerErasureOperation initial = find(operationId);
        if (initial.getState() != DocumentOwnerErasureState.OBJECT_ERASURE_PENDING) {
            return initial;
        }
        String ownerId = initial.getOwnerId();
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalStateException(
                    "Pending permanent erasure is missing its owner scope.");
        }
        ownerGuard.acquire(ownerId);
        entityManager.clear();
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() != DocumentOwnerErasureState.OBJECT_ERASURE_PENDING) {
            return operation;
        }
        if (scopeRepository.countByOperationIdAndErasedAtIsNull(operationId) != 0) {
            throw new IllegalStateException(
                    "Permanent-erasure object scopes have not all been verified.");
        }

        List<DocumentOwnerErasureScope> scopes = scopes(operationId);
        List<UUID> documentIds = scopes.stream()
                .filter(scope -> scope.getScopeType()
                        == DocumentOwnerErasureScopeType.DOCUMENT_PREFIX)
                .map(DocumentOwnerErasureScope::getDocumentId)
                .sorted()
                .toList();
        List<GeneratedDocument> documents = validateOwnerState(ownerId, documentIds);
        List<ApplicationDocumentUpload> uploads = validateUploads(
                ownerId, Set.copyOf(documentIds));
        requireExactScopes(scopes, documentIds, uploads);

        deleteOwnerData(ownerId, documents);

        LocalDateTime now = utcNow();
        operation.setOwnerId(null);
        operation.setState(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        operation.setLiveDataErasedAt(now);
        operation.setBackupRetentionUntil(
                now.plusDays(operation.getBackupRetentionDays()));
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional
    public DocumentOwnerErasureOperation eraseRestoredDatabase(UUID operationId) {
        DocumentOwnerErasureOperation initial = find(operationId);
        if (initial.getState() != DocumentOwnerErasureState.RESTORE_REPLAY_PENDING) {
            return initial;
        }
        String ownerId = initial.getOwnerId();
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalStateException(
                    "Pending restore replay is missing its owner scope.");
        }
        ownerGuard.acquire(ownerId);
        entityManager.clear();
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() != DocumentOwnerErasureState.RESTORE_REPLAY_PENDING) {
            return operation;
        }
        if (scopeRepository.countByOperationIdAndErasedAtIsNull(operationId) != 0) {
            throw new IllegalStateException(
                    "Restore-replay object scopes have not all been verified.");
        }
        List<DocumentOwnerErasureScope> retainedScopes = scopes(operationId);
        List<UUID> allowedDocumentIds = retainedScopes.stream()
                .filter(scope -> scope.getScopeType()
                        == DocumentOwnerErasureScopeType.DOCUMENT_PREFIX)
                .map(DocumentOwnerErasureScope::getDocumentId)
                .sorted()
                .toList();
        List<GeneratedDocument> restoredDocuments =
                requireRestoredOwnerWithinScopes(
                        ownerId,
                        Set.copyOf(allowedDocumentIds),
                        retainedScopes.stream()
                                .map(DocumentOwnerErasureScope::getStorageScope)
                                .collect(java.util.stream.Collectors.toSet()));
        deleteOwnerData(ownerId, restoredDocuments);

        LocalDateTime now = utcNow();
        operation.setOwnerId(null);
        operation.setState(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        operation.setLiveDataErasedAt(now);
        operation.setRestoreReplayObjectErasedAt(now);
        operation.setBackupRetentionUntil(
                now.plusDays(operation.getBackupRetentionDays()));
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional
    public DocumentOwnerErasureOperation attestBackupExpiry(
            String ownerId,
            UUID operationId,
            String evidenceReference,
            String operatorId) {
        requirePolicy();
        DocumentOwnerErasureOperation owned = requireOwned(ownerId, operationId);
        DocumentOwnerErasureOperation operation = lock(owned.getOperationId());
        if (operation.getState() != DocumentOwnerErasureState.BACKUP_RETENTION_PENDING
                && operation.getState() != DocumentOwnerErasureState.COMPLETED) {
            throw new OperationConflictException(
                    "Live data erasure must complete before backup expiry can be attested.");
        }
        if (!operation.getBackupRetentionPolicyVersion().equals(
                        properties.getBackupRetentionPolicyVersion().trim())
                || operation.getBackupRetentionDays()
                        != properties.getMaximumBackupRetentionDays()) {
            throw new OperationConflictException(
                    "Backup expiry attestation does not match the snapshotted backup policy.");
        }
        String evidenceSha256 = evidenceSha256(evidenceReference);
        if (operation.getBackupExpiryEvidenceSha256() != null) {
            if (!constantTimeEquals(
                    operation.getBackupExpiryEvidenceSha256(), evidenceSha256)) {
                throw new OperationConflictException(
                        "Backup expiry was already attested with different evidence.");
            }
            return operation;
        }
        LocalDateTime now = utcNow();
        if (operation.getBackupRetentionUntil() == null
                || now.isBefore(operation.getBackupRetentionUntil())) {
            throw new OperationConflictException(
                    "Backup expiry cannot be attested before the snapshotted recovery window ends.");
        }
        operation.setBackupExpiryEvidenceSha256(evidenceSha256);
        operation.setBackupExpiryAttestedBy(normalizeOperator(operatorId));
        operation.setBackupExpiryAttestedAt(now);
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional
    public DocumentOwnerErasureOperation completeBackupRetention(UUID operationId) {
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() == DocumentOwnerErasureState.COMPLETED) {
            return operation;
        }
        if (operation.getState()
                != DocumentOwnerErasureState.BACKUP_RETENTION_PENDING) {
            throw new IllegalStateException(
                    "Live data must be erased before backup retention can complete.");
        }
        LocalDateTime now = utcNow();
        if (operation.getBackupRetentionUntil() == null
                || now.isBefore(operation.getBackupRetentionUntil())) {
            return operation;
        }
        if (operation.getBackupExpiryEvidenceSha256() == null
                || operation.getBackupExpiryAttestedBy() == null
                || operation.getBackupExpiryAttestedAt() == null
                || operation.getBackupExpiryAttestedAt().isBefore(
                        operation.getBackupRetentionUntil())) {
            return operation;
        }
        if (!operation.getBackupRetentionPolicyVersion().equals(
                        properties.getBackupRetentionPolicyVersion().trim())
                || operation.getBackupRetentionDays()
                        != properties.getMaximumBackupRetentionDays()) {
            throw new OperationConflictException(
                    "Backup expiry attestation does not match the snapshotted backup policy.");
        }
        operation.setState(DocumentOwnerErasureState.COMPLETED);
        operation.setCompletedAt(now);
        return operationRepository.saveAndFlush(operation);
    }

    private void deleteOwnerData(
            String ownerId, List<GeneratedDocument> documents) {
        List<UUID> persistedDocumentIds = documents.stream()
                .map(GeneratedDocument::getId)
                .toList();
        if (!persistedDocumentIds.isEmpty()) {
            tombstoneRepository.deleteByDocumentIdIn(persistedDocumentIds);
            tombstoneRepository.flush();
            fileRepository.deleteByGeneratedDocumentIdIn(persistedDocumentIds);
            fileRepository.flush();
        }
        lifecycleEventRepository.deleteByOwnerId(ownerId);
        lifecycleEventRepository.flush();
        activityEventRepository.deleteByOwnerId(ownerId);
        activityEventRepository.flush();
        currentCommandRepository.deleteByOwnerId(ownerId);
        currentCommandRepository.flush();
        workflowRepository.deleteByOwnerId(ownerId);
        workflowRepository.flush();
        uploadRepository.deleteByOwnerId(ownerId);
        uploadRepository.flush();
        storageOperationRepository.deleteByOwnerId(ownerId);
        storageOperationRepository.flush();
        if (!documents.isEmpty()) {
            documentRepository.deleteAll(documents);
            documentRepository.flush();
        }
    }

    private void requireRestoredOwnerWithinJournal(
            String ownerId, PermanentErasureRecoveryJournalRecord record) {
        requireRestoredOwnerWithinScopes(
                ownerId,
                Set.copyOf(record.documentIds()),
                record.objectScopes().stream()
                        .map(PermanentErasureRecoveryJournalRecord.Scope::storageScope)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private List<GeneratedDocument> requireRestoredOwnerWithinScopes(
            String ownerId,
            Set<UUID> allowedDocumentIds,
            Set<String> allowedStorageScopes) {
        List<GeneratedDocument> documents = documentRepository.findByUserId(ownerId)
                .stream()
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .toList();
        if (documents.stream()
                .map(GeneratedDocument::getId)
                .anyMatch(id -> !allowedDocumentIds.contains(id))) {
            throw new OperationConflictException(
                    "Restored owner data exceeds the immutable recovery-journal scope.");
        }
        Set<UUID> restoredDocumentIds = documents.stream()
                .map(GeneratedDocument::getId)
                .collect(java.util.stream.Collectors.toSet());
        int ownedFiles = Math.toIntExact(fileRepository.countByOwnerId(ownerId));
        int scopedFiles = restoredDocumentIds.isEmpty()
                ? 0
                : fileRepository
                        .findByGeneratedDocumentIdInAndOwnerIdOrderByCreatedAtAsc(
                                restoredDocumentIds.stream().sorted().toList(), ownerId)
                        .size();
        if (ownedFiles != scopedFiles) {
            throw new OperationConflictException(
                    "Restored file data exceeds the immutable recovery-journal scope.");
        }
        if (storageOperationRepository.findByOwnerId(ownerId).stream()
                .anyMatch(operation -> !allowedDocumentIds.contains(
                        operation.getGeneratedDocumentId()))) {
            throw new OperationConflictException(
                    "Restored storage evidence exceeds the immutable recovery-journal scope.");
        }
        for (ApplicationDocumentUpload upload : uploadRepository.findByOwnerId(ownerId)) {
            if ((upload.getDocumentId() != null
                            && !allowedDocumentIds.contains(upload.getDocumentId()))
                    || !allowedStorageScopes.contains(
                            ObjectKeyFactory.forUploadQuarantine(upload.getId()))) {
                throw new OperationConflictException(
                        "Restored upload data exceeds the immutable recovery-journal scope.");
            }
        }
        return documents;
    }

    private void resetScopesForRestore(UUID operationId) {
        List<DocumentOwnerErasureScope> scopes =
                scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId);
        scopes.forEach(scope -> scope.setErasedAt(null));
        scopeRepository.saveAllAndFlush(scopes);
    }

    private void requireCurrentBackupPolicy(
            PermanentErasureRecoveryJournalRecord record) {
        if (!record.policyVersion().equals(properties.getPolicyVersion().trim())
                || !record.backupRetentionPolicyVersion().equals(
                        properties.getBackupRetentionPolicyVersion().trim())
                || record.backupRetentionDays()
                        != properties.getMaximumBackupRetentionDays()) {
            throw new OperationConflictException(
                    "Restore replay does not match the currently approved retention policy.");
        }
    }

    private void requireJournalMatchesOperation(
            DocumentOwnerErasureOperation operation,
            PermanentErasureRecoveryJournalRecord record,
            PermanentErasureJournalEntry evidence) {
        String expectedKey = PermanentErasureJournalKeys.forOperation(
                operation.getOperationId());
        if (!operation.isJournalRequired()
                || !PermanentErasureRecoveryJournalRecord.SCHEMA_VERSION.equals(
                        operation.getJournalSchemaVersion())
                || !operation.getJournalSchemaVersion().equals(record.schemaVersion())
                || !expectedKey.equals(operation.getJournalObjectKey())
                || !expectedKey.equals(evidence.objectKey())
                || !constantTimeEquals(
                        operation.getJournalObjectVersion(),
                        evidence.objectVersion())
                || !constantTimeEquals(
                        operation.getJournalContentSha256(),
                        evidence.contentSha256())
                || !constantTimeEquals(
                        operation.getRequestSha256(), record.requestSha256())
                || !constantTimeEquals(
                        operation.getApprovalReferenceSha256(),
                        record.approvalReferenceSha256())
                || !operation.getPolicyVersion().equals(record.policyVersion())
                || !operation.getBackupRetentionPolicyVersion().equals(
                        record.backupRetentionPolicyVersion())
                || operation.getBackupRetentionDays() != record.backupRetentionDays()
                || operation.getDocumentCount() != record.documentIds().size()
                || operation.getObjectScopeCount() != record.objectScopes().size()) {
            throw new OperationConflictException(
                    "Restore replay does not match retained recovery-journal evidence.");
        }
        List<PermanentErasureRecoveryJournalRecord.Scope> retainedScopes =
                scopes(operation.getOperationId()).stream()
                        .map(scope -> new PermanentErasureRecoveryJournalRecord.Scope(
                                scope.getScopeType(),
                                scope.getDocumentId(),
                                scope.getStorageScope()))
                        .toList();
        if (!retainedScopes.equals(record.objectScopes())) {
            throw new OperationConflictException(
                    "Restore replay scopes do not match retained recovery-journal evidence.");
        }
    }

    private void requireLegacyOperationMatchesJournal(
            DocumentOwnerErasureOperation operation,
            PermanentErasureRecoveryJournalRecord record,
            PermanentErasureJournalEntry evidence) {
        String expectedKey = PermanentErasureJournalKeys.forOperation(
                operation.getOperationId());
        if (operation.isJournalRequired()
                || operation.getJournalSchemaVersion() != null
                || operation.getJournalObjectKey() != null
                || operation.getJournalObjectVersion() != null
                || operation.getJournalContentSha256() != null
                || operation.getJournalRecordedAt() != null
                || !expectedKey.equals(evidence.objectKey())
                || !constantTimeEquals(
                        operation.getRequestSha256(), record.requestSha256())
                || !constantTimeEquals(
                        operation.getApprovalReferenceSha256(),
                        record.approvalReferenceSha256())
                || !operation.getPolicyVersion().equals(record.policyVersion())
                || !operation.getBackupRetentionPolicyVersion().equals(
                        record.backupRetentionPolicyVersion())
                || operation.getBackupRetentionDays() != record.backupRetentionDays()
                || operation.getDocumentCount() != record.documentIds().size()
                || operation.getObjectScopeCount() != record.objectScopes().size()) {
            throw new OperationConflictException(
                    "Restore replay does not match retained recovery-journal evidence.");
        }
        List<PermanentErasureRecoveryJournalRecord.Scope> retainedScopes =
                scopes(operation.getOperationId()).stream()
                        .map(scope -> new PermanentErasureRecoveryJournalRecord.Scope(
                                scope.getScopeType(),
                                scope.getDocumentId(),
                                scope.getStorageScope()))
                        .toList();
        if (!retainedScopes.equals(record.objectScopes())) {
            throw new OperationConflictException(
                    "Restore replay scopes do not match retained recovery-journal evidence.");
        }
    }

    private void bindRecoveredJournal(
            DocumentOwnerErasureOperation operation,
            PermanentErasureRecoveryJournalRecord record,
            PermanentErasureJournalEntry evidence) {
        operation.setJournalRequired(true);
        operation.setJournalSchemaVersion(record.schemaVersion());
        operation.setJournalObjectKey(evidence.objectKey());
        operation.setJournalObjectVersion(evidence.objectVersion());
        operation.setJournalContentSha256(evidence.contentSha256());
        operation.setJournalRecordedAt(utcNow());
    }

    private void requireOwnerFingerprint(
            DocumentOwnerErasureOperation operation, List<String> fingerprints) {
        if (fingerprints.stream().noneMatch(value ->
                constantTimeEquals(operation.getOwnerFingerprint(), value))) {
            throw new ResourceNotFoundException(
                    "Permanent-erasure operation not found.");
        }
    }

    private List<GeneratedDocument> validateOwnerState(
            String ownerId, List<UUID> requestedIds) {
        List<GeneratedDocument> documents = documentRepository.findByUserId(ownerId)
                .stream()
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .toList();
        List<UUID> actualIds = documents.stream().map(GeneratedDocument::getId).toList();
        if (!actualIds.equals(requestedIds)) {
            throw new OperationConflictException(
                    "Permanent erasure requires the exact current owner document set.");
        }
        LocalDateTime now = utcNow();
        for (GeneratedDocument document : documents) {
            if (document.isLegalHold()) {
                throw new OperationConflictException(
                        "Permanent erasure is blocked by a legal hold.");
            }
            if (document.getRetentionState() == DocumentRetentionState.PURGED) {
                continue;
            }
            if (document.getRetentionState() != DocumentRetentionState.DELETED
                    || document.getPurgeEligibleAt() == null
                    || now.isBefore(document.getPurgeEligibleAt())) {
                throw new OperationConflictException(
                        "Every document must complete its recovery period before permanent erasure.");
            }
        }
        if (storageOperationRepository.existsByOwnerIdAndState(
                ownerId, StorageOperationState.PREPARED)) {
            throw new OperationConflictException(
                    "Permanent erasure is blocked by an unresolved storage operation.");
        }
        Set<UUID> requested = Set.copyOf(requestedIds);
        boolean foreignOperationScope = storageOperationRepository.findByOwnerId(ownerId)
                .stream()
                .anyMatch(operation -> !requested.contains(
                        operation.getGeneratedDocumentId()));
        if (foreignOperationScope) {
            throw new OperationConflictException(
                    "Permanent erasure storage evidence does not match the exact document set.");
        }
        int ownedFiles = Math.toIntExact(fileRepository.countByOwnerId(ownerId));
        int scopedFiles = requestedIds.isEmpty()
                ? 0
                : fileRepository
                        .findByGeneratedDocumentIdInAndOwnerIdOrderByCreatedAtAsc(
                                requestedIds, ownerId)
                        .size();
        if (ownedFiles != scopedFiles) {
            throw new OperationConflictException(
                    "Permanent erasure file evidence does not match the exact document set.");
        }
        return documents;
    }

    private List<ApplicationDocumentUpload> validateUploads(
            String ownerId, Set<UUID> documentIds) {
        List<ApplicationDocumentUpload> uploads = uploadRepository.findByOwnerId(ownerId)
                .stream()
                .sorted((left, right) -> left.getId().compareTo(right.getId()))
                .toList();
        for (ApplicationDocumentUpload upload : uploads) {
            if (!upload.getState().terminal()) {
                throw new OperationConflictException(
                        "Permanent erasure is blocked while an application upload is processing.");
            }
            if (upload.getDocumentId() != null
                    && !documentIds.contains(upload.getDocumentId())) {
                throw new OperationConflictException(
                        "Permanent erasure upload evidence does not match the exact document set.");
            }
            String canonical = ObjectKeyFactory.forUploadQuarantine(upload.getId());
            if (upload.getQuarantineKey() != null
                    && !canonical.equals(upload.getQuarantineKey())) {
                throw new OperationConflictException(
                        "Permanent erasure upload storage evidence is invalid.");
            }
        }
        return uploads;
    }

    private List<DocumentOwnerErasureScope> scopes(
            UUID operationId,
            List<UUID> documentIds,
            List<ApplicationDocumentUpload> uploads) {
        java.util.ArrayList<DocumentOwnerErasureScope> scopes = new java.util.ArrayList<>();
        for (UUID documentId : documentIds) {
            scopes.add(DocumentOwnerErasureScope.builder()
                    .id(UUID.randomUUID())
                    .operationId(operationId)
                    .scopeType(DocumentOwnerErasureScopeType.DOCUMENT_PREFIX)
                    .documentId(documentId)
                    .storageScope(documentPrefix(documentId))
                    .build());
        }
        for (ApplicationDocumentUpload upload : uploads) {
            scopes.add(DocumentOwnerErasureScope.builder()
                    .id(UUID.randomUUID())
                    .operationId(operationId)
                    .scopeType(DocumentOwnerErasureScopeType.UPLOAD_KEY)
                    .storageScope(ObjectKeyFactory.forUploadQuarantine(upload.getId()))
                    .build());
        }
        scopes.sort((left, right) -> left.getStorageScope()
                .compareTo(right.getStorageScope()));
        return List.copyOf(scopes);
    }

    private void requireExactScopes(
            List<DocumentOwnerErasureScope> scopes,
            List<UUID> documentIds,
            List<ApplicationDocumentUpload> uploads) {
        Set<String> expected = new HashSet<>();
        documentIds.forEach(id -> expected.add(documentPrefix(id)));
        uploads.forEach(upload -> expected.add(
                ObjectKeyFactory.forUploadQuarantine(upload.getId())));
        Set<String> actual = scopes.stream()
                .map(DocumentOwnerErasureScope::getStorageScope)
                .collect(java.util.stream.Collectors.toSet());
        if (scopes.size() != actual.size() || !expected.equals(actual)) {
            throw new OperationConflictException(
                    "Permanent erasure object scopes no longer match the exact owner data set.");
        }
    }

    private DocumentOwnerErasureOperation lock(UUID operationId) {
        return operationRepository.lockByOperationId(operationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Permanent-erasure operation not found."));
    }

    private List<UUID> normalizeDocumentIds(List<UUID> documentIds) {
        if (documentIds == null || documentIds.size() > 2000) {
            throw new IllegalArgumentException(
                    "Permanent-erasure documentIds must contain at most 2000 items.");
        }
        if (documentIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new IllegalArgumentException(
                    "Permanent-erasure documentIds must be non-null and distinct.");
        }
        return documentIds.stream().sorted().toList();
    }

    private String approvalSha256(String approvalReference) {
        if (approvalReference == null
                || approvalReference.isBlank()
                || approvalReference.trim().length() > 128) {
            throw new IllegalArgumentException(
                    "A bounded permanent-erasure approval reference is required.");
        }
        return OperationFingerprint.sha256(approvalReference.trim());
    }

    private String evidenceSha256(String evidenceReference) {
        if (evidenceReference == null
                || evidenceReference.isBlank()
                || evidenceReference.trim().length() > 256) {
            throw new IllegalArgumentException(
                    "A bounded backup-expiry evidence reference is required.");
        }
        return OperationFingerprint.sha256(evidenceReference.trim());
    }

    private String restoreEvidenceSha256(String evidenceReference) {
        if (evidenceReference == null
                || evidenceReference.isBlank()
                || evidenceReference.trim().length() > 256) {
            throw new IllegalArgumentException(
                    "A bounded restore-replay evidence reference is required.");
        }
        return OperationFingerprint.sha256(evidenceReference.trim());
    }

    private String normalizeOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || ownerId.trim().length() > 255) {
            throw new IllegalArgumentException("Document owner is required.");
        }
        return ownerId.trim();
    }

    private String normalizeOperator(String operatorId) {
        if (operatorId == null
                || operatorId.isBlank()
                || operatorId.trim().length() > 64) {
            throw new IllegalArgumentException(
                    "Permanent-erasure operator identity is required.");
        }
        return operatorId.trim();
    }

    private String documentPrefix(UUID documentId) {
        return "documents/" + documentId + "/";
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private void requireReplay(
            DocumentOwnerErasureOperation existing,
            List<String> fingerprints,
            String requestSha256) {
        if (fingerprints.stream().noneMatch(fingerprint ->
                        constantTimeEquals(
                                existing.getOwnerFingerprint(), fingerprint))
                || !constantTimeEquals(existing.getRequestSha256(), requestSha256)) {
            throw new OperationConflictException(
                    "Permanent-erasure operation was reused with a different owner or request.");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        actual.getBytes(StandardCharsets.UTF_8));
    }

    private void requirePolicy() {
        try {
            properties.requireApprovedPermanentErasurePolicy();
            journalProperties.requireConfigured();
        } catch (IllegalStateException exception) {
            throw new OperationConflictException(exception.getMessage());
        }
    }

    public record StatusSnapshot(
            DocumentOwnerErasureOperation operation,
            DocumentOwnerErasureRestoreRequest restoreRequest) {
    }
}

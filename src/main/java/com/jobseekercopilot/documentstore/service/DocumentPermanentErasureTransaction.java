package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureOperation;
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
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureScopeRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentTombstoneAssociationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
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
    private final EntityManager entityManager;
    private final DocumentOwnerFingerprint ownerFingerprint;
    private final DocumentOwnerErasureGuard ownerGuard;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureOperationRepository operationRepository;
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
        String normalizedOwner = normalizeOwner(ownerId);
        String fingerprint = ownerFingerprint.fingerprint(normalizedOwner);
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
            requireReplay(existing, fingerprint, requestSha256);
            return existing;
        }
        if (operationRepository.findByOwnerFingerprint(fingerprint).isPresent()) {
            throw new OperationConflictException(
                    "A permanent-erasure operation already exists for this owner.");
        }

        List<GeneratedDocument> documents = validateOwnerState(
                normalizedOwner, requestedIds);
        List<ApplicationDocumentUpload> uploads = validateUploads(
                normalizedOwner, Set.copyOf(requestedIds));
        LocalDateTime now = LocalDateTime.now();
        DocumentOwnerErasureOperation operation = operationRepository.saveAndFlush(
                DocumentOwnerErasureOperation.builder()
                        .operationId(operationId)
                        .ownerId(normalizedOwner)
                        .ownerFingerprint(fingerprint)
                        .fingerprintKeyVerifier(ownerFingerprint.keyVerifier())
                        .requestSha256(requestSha256)
                        .approvalReferenceSha256(approvalSha256)
                        .operatorId(normalizedOperator)
                        .state(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING)
                        .documentCount(documents.size())
                        .objectScopeCount(0)
                        .attemptCount(0)
                        .policyVersion(properties.getPolicyVersion().trim())
                        .backupRetentionPolicyVersion(properties
                                .getBackupRetentionPolicyVersion()
                                .trim())
                        .backupRetentionDays(
                                properties.getMaximumBackupRetentionDays())
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        List<DocumentOwnerErasureScope> scopes = scopes(operationId, requestedIds, uploads);
        scopeRepository.saveAllAndFlush(scopes);
        operation.setObjectScopeCount(scopes.size());
        return operationRepository.saveAndFlush(operation);
    }

    @Transactional(readOnly = true)
    public DocumentOwnerErasureOperation requireOwned(
            String ownerId, UUID operationId) {
        DocumentOwnerErasureOperation operation = operationRepository
                .findById(operationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Permanent-erasure operation not found."));
        String supplied = ownerFingerprint.fingerprint(normalizeOwner(ownerId));
        if (!constantTimeEquals(operation.getOwnerFingerprint(), supplied)) {
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

    @Transactional
    public DocumentOwnerErasureOperation markAttempt(UUID operationId) {
        DocumentOwnerErasureOperation operation = lock(operationId);
        if (operation.getState() != DocumentOwnerErasureState.COMPLETED) {
            operation.setAttemptCount(operation.getAttemptCount() + 1);
            operation.setLastAttemptAt(LocalDateTime.now());
            operationRepository.saveAndFlush(operation);
        }
        return operation;
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

        LocalDateTime now = LocalDateTime.now();
        operation.setOwnerId(null);
        operation.setState(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        operation.setLiveDataErasedAt(now);
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
        if (operation.getState() == DocumentOwnerErasureState.OBJECT_ERASURE_PENDING) {
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
        LocalDateTime now = LocalDateTime.now();
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
        LocalDateTime now = LocalDateTime.now();
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
        LocalDateTime now = LocalDateTime.now();
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

    private void requireReplay(
            DocumentOwnerErasureOperation existing,
            String fingerprint,
            String requestSha256) {
        if (!constantTimeEquals(existing.getOwnerFingerprint(), fingerprint)
                || !constantTimeEquals(existing.getRequestSha256(), requestSha256)) {
            throw new OperationConflictException(
                    "Permanent-erasure operation was reused with a different owner or request.");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
    }

    private void requirePolicy() {
        try {
            properties.requireApprovedPermanentErasurePolicy();
        } catch (IllegalStateException exception) {
            throw new OperationConflictException(exception.getMessage());
        }
    }
}

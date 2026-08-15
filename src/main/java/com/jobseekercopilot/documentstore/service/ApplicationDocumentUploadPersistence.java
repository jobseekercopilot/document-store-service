package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentUploadPersistence {

    private static final EnumSet<ApplicationDocumentUploadState> RESERVED_STATES =
            EnumSet.of(
                    ApplicationDocumentUploadState.RECEIVED,
                    ApplicationDocumentUploadState.QUARANTINED,
                    ApplicationDocumentUploadState.SCANNING,
                    ApplicationDocumentUploadState.SCANNED_CLEAN,
                    ApplicationDocumentUploadState.EXTRACTING);

    private final ApplicationDocumentUploadRepository repository;
    private final GeneratedDocumentRepository documentRepository;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureGuard ownerErasureGuard;
    private final DocumentObjectStorage objectStorage;
    private final DocumentUploadProperties properties;

    @Transactional
    public ApplicationDocumentUpload createOrReplay(
            String ownerId,
            String idempotencyKey,
            String requestSha256,
            String jobId,
            String applicationId,
            DocumentType documentType,
            FileType fileType,
            String originalSha256,
            long originalSize) {
        ownerErasureGuard.requireWritable(ownerId);
        String key = IdempotencyKeys.validate(idempotencyKey);
        if (key == null) {
            throw new IllegalArgumentException("Idempotency-Key is required.");
        }
        operationLock.acquire("application-upload-idempotency:"
                + OperationFingerprint.sha256(ownerId, key));
        ApplicationDocumentUpload replay = repository
                .findByOwnerIdAndIdempotencyKey(ownerId, key)
                .orElse(null);
        if (replay != null) {
            if (!requestSha256.equals(replay.getRequestSha256())) {
                throw new OperationConflictException(
                        "Idempotency-Key was already used for different upload content or context.");
            }
            return replay;
        }
        LocalDateTime windowStart = LocalDateTime.now()
                .minusSeconds(properties.getRateWindowSeconds());
        if (repository.countByOwnerIdAndCreatedAtAfter(ownerId, windowStart)
                >= properties.getMaximumAttemptsPerWindow()) {
            throw new OperationConflictException(
                    "Application document upload rate limit has been reached.");
        }
        try {
            return repository.saveAndFlush(ApplicationDocumentUpload.builder()
                    .id(UUID.randomUUID())
                    .ownerId(ownerId)
                    .idempotencyKey(key)
                    .requestSha256(requestSha256)
                    .jobId(jobId)
                    .applicationId(applicationId)
                    .documentType(documentType)
                    .fileType(fileType)
                    .state(ApplicationDocumentUploadState.RECEIVED)
                    .originalSha256(originalSha256)
                    .originalSize(originalSize)
                    .build());
        } catch (DataIntegrityViolationException race) {
            ApplicationDocumentUpload concurrent = repository
                    .findByOwnerIdAndIdempotencyKey(ownerId, key)
                    .orElseThrow(() -> race);
            if (!requestSha256.equals(concurrent.getRequestSha256())) {
                throw new OperationConflictException(
                        "Idempotency-Key was already used for different upload content or context.");
            }
            return concurrent;
        }
    }

    @Transactional
    public void requireQuota(ApplicationDocumentUpload upload) {
        ownerErasureGuard.requireWritable(upload.getOwnerId());
        operationLock.acquire("application-upload-quota:"
                + OperationFingerprint.sha256(upload.getOwnerId()));
        long retained = documentRepository.sumRetainedUploadedOriginalBytes(
                upload.getOwnerId());
        long reserved = repository.sumOriginalSizeByOwnerIdAndStateIn(
                upload.getOwnerId(), RESERVED_STATES);
        if (retained + reserved
                > properties.getMaximumOriginalBytesPerOwner()) {
            throw new OperationConflictException(
                    "Uploaded document storage quota has been reached.");
        }
    }

    @Transactional
    public Optional<ApplicationDocumentUpload> claimForProcessing(
            String ownerId, UUID operationId) {
        ownerErasureGuard.requireWritable(ownerId);
        int claimed = repository.claimProcessing(
                operationId,
                ownerId,
                LocalDateTime.now(),
                RESERVED_STATES);
        if (claimed == 0) {
            return Optional.empty();
        }
        return repository.findByIdAndOwnerId(operationId, ownerId);
    }

    @Transactional
    public ApplicationDocumentUpload storeQuarantine(
            String ownerId, UUID operationId, byte[] content) {
        ownerErasureGuard.requireWritable(ownerId);
        ApplicationDocumentUpload upload = required(ownerId, operationId);
        if (upload.getProcessingStartedAt() == null
                || (upload.getState() != ApplicationDocumentUploadState.RECEIVED
                        && upload.getState()
                                != ApplicationDocumentUploadState.QUARANTINED)) {
            throw new OperationConflictException(
                    "Upload processing lease is no longer active.");
        }
        if (content.length != upload.getOriginalSize()
                || !ObjectIntegrity.sha256(content).equals(
                        upload.getOriginalSha256())) {
            throw new OperationConflictException(
                    "Upload content no longer matches its processing lease.");
        }
        String quarantineKey = ObjectKeyFactory.forUploadQuarantine(operationId);
        if (upload.getQuarantineKey() != null
                && !quarantineKey.equals(upload.getQuarantineKey())) {
            throw new OperationConflictException(
                    "Upload quarantine scope is invalid.");
        }
        objectStorage.put(
                quarantineKey,
                content,
                "application/octet-stream",
                upload.getOriginalSha256());
        upload.setQuarantineKey(quarantineKey);
        upload.setState(ApplicationDocumentUploadState.QUARANTINED);
        return repository.saveAndFlush(upload);
    }

    @Transactional
    public ApplicationDocumentUpload saveGuarded(
            ApplicationDocumentUpload candidate,
            ApplicationDocumentUploadState expectedState) {
        ownerErasureGuard.requireWritable(candidate.getOwnerId());
        ApplicationDocumentUpload current = required(
                candidate.getOwnerId(), candidate.getId());
        if (current.getState() != expectedState) {
            throw new OperationConflictException(
                    "Upload processing lease is no longer active.");
        }
        current.setState(candidate.getState());
        current.setExtractedTextSha256(candidate.getExtractedTextSha256());
        current.setExtractionState(candidate.getExtractionState());
        current.setQuarantineKey(candidate.getQuarantineKey());
        current.setDocumentId(candidate.getDocumentId());
        current.setArtifactId(candidate.getArtifactId());
        current.setFailureCode(candidate.getFailureCode());
        current.setFailureMessage(candidate.getFailureMessage());
        current.setScannerEngine(candidate.getScannerEngine());
        current.setScannerVersion(candidate.getScannerVersion());
        current.setScannerSignatureAt(candidate.getScannerSignatureAt());
        current.setProcessingStartedAt(candidate.getProcessingStartedAt());
        return repository.saveAndFlush(current);
    }

    @Transactional
    public boolean cleanupQuarantine(
            String ownerId,
            UUID operationId,
            ApplicationDocumentUploadState expectedState,
            LocalDateTime updatedBefore,
            boolean failIfProcessingTimedOut) {
        ownerErasureGuard.requireWritable(ownerId);
        ApplicationDocumentUpload current = required(ownerId, operationId);
        if (current.getState() != expectedState) {
            throw new OperationConflictException(
                    "Upload processing lease is no longer active.");
        }
        if (updatedBefore != null
                && current.getUpdatedAt() != null
                && current.getUpdatedAt().isAfter(updatedBefore)) {
            return false;
        }
        if (current.getQuarantineKey() == null) {
            return true;
        }
        String canonicalKey = ObjectKeyFactory.forUploadQuarantine(operationId);
        if (!canonicalKey.equals(current.getQuarantineKey())) {
            throw new OperationConflictException(
                    "Upload quarantine scope is invalid.");
        }
        if (failIfProcessingTimedOut && !current.getState().terminal()) {
            current.setState(ApplicationDocumentUploadState.FAILED);
            current.setFailureCode("PROCESSING_TIMEOUT");
            current.setFailureMessage(
                    "Document processing did not complete within its safe recovery window.");
        }
        objectStorage.delete(canonicalKey);
        current.setQuarantineKey(null);
        repository.saveAndFlush(current);
        return true;
    }

    private ApplicationDocumentUpload required(
            String ownerId, UUID operationId) {
        return repository.findByIdAndOwnerId(operationId, ownerId)
                .orElseThrow(() -> new OperationConflictException(
                        "Upload processing lease is no longer active."));
    }
}

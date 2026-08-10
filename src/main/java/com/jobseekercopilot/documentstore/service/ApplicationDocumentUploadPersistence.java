package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
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
}

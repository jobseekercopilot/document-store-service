package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileDownload;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentFileService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileService.class);

    private final ExportedDocumentFileRepository fileRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final DocumentObjectStorage objectStorage;
    private final DocumentFileValidator fileValidator;
    private final DocumentOperationLock operationLock;
    private final DocumentStorageOperationJournal storageOperationJournal;

    @Transactional
    public DocumentFileResponse createDocumentFile(
            String ownerId,
            CreateDocumentFileRequest request,
            String requestedOperationKey) {
        long startedAt = System.nanoTime();
        requireOwnedMutableDocument(ownerId, request.getGeneratedDocumentId());
        byte[] content = fileValidator.decodeAndValidateGenerated(
                request.getFileType(),
                request.getFileName(),
                request.getMimeType(),
                request.getFileContentBase64());
        ExportedDocumentFile saved = storeMetadataAndObject(
                ownerId,
                request.getGeneratedDocumentId(),
                request.getFileType(),
                FileSource.GENERATED,
                content,
                requestedOperationKey);
        log.info("Generated document file saved fileType={} source={} durationMs={}",
                saved.getFileType(),
                saved.getSource(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
    }

    @Transactional
    public DocumentFileResponse uploadReplacementFile(
            String ownerId,
            UUID generatedDocumentId,
            MultipartFile file,
            FileType fileType,
            FileSource source,
            String requestedOperationKey) {
        long startedAt = System.nanoTime();
        log.info("Document file upload received fileType={} source={} sizeBytes={}",
                fileType,
                source,
                file == null ? 0 : file.getSize());
        requireOwnedMutableDocument(ownerId, generatedDocumentId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is required");
        }
        if (source != FileSource.USER_UPLOADED) {
            throw new IllegalArgumentException("source must be USER_UPLOADED");
        }
        fileValidator.validateDeclaredSize(file.getSize());

        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        try {
            byte[] content = file.getBytes();
            fileValidator.validateUserUpload(
                    fileType, fileName, file.getContentType(), content);
            ExportedDocumentFile saved = storeMetadataAndObject(
                    ownerId,
                    generatedDocumentId,
                    fileType,
                    source,
                    content,
                    requestedOperationKey);
            log.info("Document file upload saved fileType={} source={} durationMs={}",
                    fileType,
                    source,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return mapToResponse(saved);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded file");
        }
    }

    public DocumentFileResponse getDocumentFileMetadata(String ownerId, UUID id) {
        return mapToResponse(findActiveDocumentFile(ownerId, id));
    }

    public DocumentFileDownload downloadDocumentFile(String ownerId, UUID id) {
        long startedAt = System.nanoTime();
        ExportedDocumentFile file = findActiveDocumentFile(ownerId, id);
        byte[] content = objectStorage.get(file.getStorageKey());
        String actualSha256 = ObjectIntegrity.sha256(content);
        if (content.length != file.getContentSize()
                || !actualSha256.equals(file.getContentSha256())) {
            file.setStorageStatus(ObjectStorageStatus.UNAVAILABLE);
            fileRepository.save(file);
            log.error("Document object integrity verification failed");
            throw new ObjectStorageException("Document object failed integrity verification");
        }
        try {
            fileValidator.validateStored(file.getFileType(), content);
        } catch (IllegalArgumentException exception) {
            file.setStorageStatus(ObjectStorageStatus.UNAVAILABLE);
            fileRepository.save(file);
            log.error("Document object safety validation failed");
            throw new ObjectStorageException("Document object failed safety validation");
        }
        log.info("Document file loaded fileType={} source={} sizeBytes={} durationMs={}",
                file.getFileType(),
                file.getSource(),
                content.length,
                (System.nanoTime() - startedAt) / 1_000_000);
        return new DocumentFileDownload(
                fileValidator.safeFileName(file.getId(), file.getFileType()),
                fileValidator.canonicalMimeType(file.getFileType()),
                content);
    }

    private ExportedDocumentFile findActiveDocumentFile(String ownerId, UUID id) {
        ExportedDocumentFile file = fileRepository.findByIdAndGeneratedDocument_UserId(id, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        requireOwnedVisibleDocument(ownerId, file.getGeneratedDocumentId());
        if (!file.isActive() || file.getStorageStatus() != ObjectStorageStatus.AVAILABLE) {
            log.warn("Inactive or unavailable document file access rejected");
            throw ResourceNotFoundException.documentFileNotFound();
        }
        return file;
    }

    public List<DocumentFileResponse> getFilesForDocument(String ownerId, UUID generatedDocumentId) {
        requireOwnedVisibleDocument(ownerId, generatedDocumentId);
        return fileRepository
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdOrderByCreatedAtDesc(
                        generatedDocumentId,
                        ownerId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public List<DocumentFileResponse> getLatestFilesForDocument(
            String ownerId,
            UUID generatedDocumentId) {
        requireOwnedVisibleDocument(ownerId, generatedDocumentId);
        return fileRepository
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndActiveTrueOrderByUpdatedAtDesc(
                        generatedDocumentId,
                        ownerId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public DocumentFileResponse activateFileVersion(String ownerId, UUID fileId) {
        ExportedDocumentFile selected = fileRepository
                .findByIdAndGeneratedDocument_UserId(fileId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        requireOwnedMutableDocument(ownerId, selected.getGeneratedDocumentId());
        operationLock.acquire(lockScope(
                "document-file-version",
                selected.getGeneratedDocumentId(),
                selected.getFileType()));
        selected = fileRepository
                .findByIdAndGeneratedDocument_UserId(fileId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        if (selected.getStorageStatus() != ObjectStorageStatus.AVAILABLE) {
            throw ResourceNotFoundException.documentFileNotFound();
        }
        if (selected.isActive()) {
            return mapToResponse(selected);
        }
        deactivateCurrentFile(
                ownerId, selected.getGeneratedDocumentId(), selected.getFileType());
        selected.setActive(true);
        ExportedDocumentFile restored = fileRepository.saveAndFlush(selected);
        log.info("Previous document file version restored fileType={} version={}",
                restored.getFileType(), restored.getVersion());
        return mapToResponse(restored);
    }

    private void deactivateCurrentFile(
            String ownerId,
            UUID generatedDocumentId,
            FileType fileType) {
        List<ExportedDocumentFile> activeFiles =
                fileRepository
                        .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                                generatedDocumentId,
                                ownerId,
                                fileType);
        activeFiles.forEach(file -> file.setActive(false));
        fileRepository.saveAllAndFlush(activeFiles);
        if (!activeFiles.isEmpty()) {
            log.info("Previous active document files deactivated fileType={} count={}",
                    fileType, activeFiles.size());
        }
    }

    private ExportedDocumentFile storeMetadataAndObject(
            String ownerId,
            UUID generatedDocumentId,
            FileType fileType,
            FileSource source,
            byte[] content,
            String requestedOperationKey) {
        String operationKey = IdempotencyKeys.validate(requestedOperationKey);
        String requestSha256 = OperationFingerprint.sha256(
                generatedDocumentId,
                fileType,
                source,
                OperationFingerprint.contentSha256(content));
        if (operationKey != null) {
            operationLock.acquire(lockScope(
                    "document-file-idempotency", ownerId, operationKey));
            ExportedDocumentFile replay = fileRepository
                    .findByOwnerIdAndOperationKey(ownerId, operationKey)
                    .orElse(null);
            if (replay != null) {
                requireMatchingFingerprint(replay.getRequestSha256(), requestSha256);
                log.info("Document file idempotent retry replayed fileType={} version={}",
                        replay.getFileType(), replay.getVersion());
                return replay;
            }
        }

        operationLock.acquire(lockScope(
                "document-file-version", generatedDocumentId, fileType));
        int metadataVersion = fileRepository
                .findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
                        generatedDocumentId, fileType)
                .map(ExportedDocumentFile::getVersion)
                .orElse(0);
        int reservedVersion =
                storageOperationJournal.highestReservedVersion(generatedDocumentId, fileType);
        int proposedVersion = Math.max(metadataVersion, reservedVersion) + 1;
        StorageOperationReservation reservation = storageOperationJournal.prepare(
                ownerId,
                generatedDocumentId,
                fileType,
                proposedVersion,
                operationKey,
                requestSha256);
        int version = reservation.fileVersion();
        UUID fileId = reservation.fileId();
        String fileName = fileValidator.safeFileName(fileId, fileType);
        String mimeType = fileValidator.canonicalMimeType(fileType);
        String storageKey = reservation.storageKey();
        String sha256 = ObjectIntegrity.sha256(content);
        objectStorage.put(storageKey, content, mimeType, sha256);
        try {
            deactivateCurrentFile(ownerId, generatedDocumentId, fileType);
            ExportedDocumentFile saved = fileRepository.saveAndFlush(
                    ExportedDocumentFile.builder()
                    .id(fileId)
                    .generatedDocumentId(generatedDocumentId)
                    .ownerId(ownerId)
                    .fileType(fileType)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .source(source)
                    .active(true)
                    .version(version)
                    .operationKey(operationKey)
                    .requestSha256(operationKey == null ? null : requestSha256)
                    .storageKey(storageKey)
                    .contentSize(content.length)
                    .contentSha256(sha256)
                    .storageStatus(ObjectStorageStatus.AVAILABLE)
                    .build());
            storageOperationJournal.markCommitted(fileId);
            return saved;
        } catch (RuntimeException exception) {
            try {
                objectStorage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Document object compensation failed; DOC-08 reconciliation required");
            }
            throw exception;
        }
    }

    private void requireMatchingFingerprint(String stored, String requested) {
        if (!requested.equals(stored)) {
            throw new OperationConflictException(
                    "Idempotency-Key was already used for a different document file operation.");
        }
    }

    private String lockScope(String prefix, Object... parts) {
        return prefix + ":" + OperationFingerprint.sha256(parts);
    }

    private void requireOwnedMutableDocument(String ownerId, UUID generatedDocumentId) {
        var initial = documentRepository.findByIdAndUserId(generatedDocumentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        operationLock.acquire(lockScope(
                "document-family", ownerId, initial.getDocumentFamilyId()));
        var document = documentRepository.findByIdAndUserId(generatedDocumentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        if (document.getRetentionState() != DocumentRetentionState.AVAILABLE) {
            throw new OperationConflictException(
                    "Archived or deleted documents cannot accept file changes.");
        }
    }

    private void requireOwnedVisibleDocument(String ownerId, UUID generatedDocumentId) {
        var document = documentRepository.findByIdAndUserId(generatedDocumentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        if (document.getRetentionState() == DocumentRetentionState.DELETED) {
            throw ResourceNotFoundException.documentNotFound();
        }
    }

    private DocumentFileResponse mapToResponse(ExportedDocumentFile file) {
        return DocumentFileResponse.builder()
                .id(file.getId())
                .generatedDocumentId(file.getGeneratedDocumentId())
                .fileType(file.getFileType())
                .fileName(fileValidator.safeFileName(file.getId(), file.getFileType()))
                .mimeType(fileValidator.canonicalMimeType(file.getFileType()))
                .source(file.getSource())
                .active(file.isActive())
                .version(file.getVersion())
                .contentSize(file.getContentSize())
                .contentSha256(file.getContentSha256())
                .storageStatus(file.getStorageStatus())
                .storedAt(file.getStoredAt())
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }
}

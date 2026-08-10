package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileDownload;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final DocumentStoreMetrics metrics;
    private final DocumentStorageOperationJournal storageOperationJournal;
    private final DocumentActivityService activityService;

    @Transactional
    public DocumentFileResponse createDocumentFile(
            String ownerId,
            CreateDocumentFileRequest request,
            String requestedOperationKey) {
        return metrics.observe(
                "file",
                "store_export",
                null,
                request.getFileType(),
                () -> createDocumentFileInternal(
                        ownerId, request, requestedOperationKey));
    }

    private DocumentFileResponse createDocumentFileInternal(
            String ownerId,
            CreateDocumentFileRequest request,
            String requestedOperationKey) {
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
        metrics.recordPayload(
                "file",
                "stored",
                null,
                saved.getFileType(),
                content.length);
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
        return metrics.observe(
                "file",
                "replace_export",
                null,
                fileType,
                () -> uploadReplacementFileInternal(
                        ownerId,
                        generatedDocumentId,
                        file,
                        fileType,
                        source,
                        requestedOperationKey));
    }

    private DocumentFileResponse uploadReplacementFileInternal(
            String ownerId,
            UUID generatedDocumentId,
            MultipartFile file,
            FileType fileType,
            FileSource source,
            String requestedOperationKey) {
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
            metrics.recordPayload(
                    "file",
                    "stored",
                    null,
                    fileType,
                    content.length);
            return mapToResponse(saved);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded file");
        }
    }

    @Transactional
    public DocumentFileResponse storeApplicationUploadArtifact(
            String ownerId,
            UUID generatedDocumentId,
            FileType fileType,
            String originalFileName,
            String declaredMimeType,
            byte[] content,
            String requestedOperationKey) {
        return metrics.observe(
                "file",
                "store_upload",
                null,
                fileType,
                () -> {
                    requireOwnedMutableDocument(ownerId, generatedDocumentId);
                    fileValidator.validateApplicationUpload(
                            fileType,
                            originalFileName,
                            declaredMimeType,
                            content);
                    ExportedDocumentFile saved = storeMetadataAndObject(
                            ownerId,
                            generatedDocumentId,
                            fileType,
                            FileSource.USER_UPLOADED,
                            content,
                            requestedOperationKey);
                    metrics.recordPayload(
                            "file", "stored", null, fileType, content.length);
                    return mapToResponse(saved);
                });
    }

    public DocumentFileResponse getDocumentFileMetadata(String ownerId, UUID id) {
        return metrics.observe(
                "file",
                "retrieve_export",
                null,
                null,
                () -> mapToResponse(findActiveDocumentFile(ownerId, id)));
    }

    public DocumentFileDownload downloadDocumentFile(String ownerId, UUID id) {
        return metrics.observe(
                "file",
                "retrieve_export",
                null,
                null,
                () -> downloadDocumentFileInternal(ownerId, id));
    }

    public DocumentFileDownload downloadDocumentArtifact(
            String ownerId,
            UUID generatedDocumentId,
            UUID artifactId) {
        return metrics.observe(
                "file",
                "retrieve_export",
                null,
                null,
                () -> downloadDocumentArtifactInternal(
                        ownerId, generatedDocumentId, artifactId));
    }

    private DocumentFileDownload downloadDocumentFileInternal(
            String ownerId, UUID id) {
        ExportedDocumentFile file = fileRepository
                .findByIdAndOwnerIdAndGeneratedDocument_UserId(
                        id, ownerId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        GeneratedDocument document = requireOwnedDownloadableDocument(
                ownerId, file.getGeneratedDocumentId());
        return downloadAndRecord(file, document);
    }

    private DocumentFileDownload downloadDocumentArtifactInternal(
            String ownerId,
            UUID generatedDocumentId,
            UUID artifactId) {
        ExportedDocumentFile file = fileRepository
                .findByIdAndGeneratedDocumentIdAndOwnerIdAndGeneratedDocument_UserId(
                        artifactId,
                        generatedDocumentId,
                        ownerId,
                        ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        GeneratedDocument document = requireOwnedDownloadableDocument(
                ownerId, generatedDocumentId);
        return downloadAndRecord(file, document);
    }

    private DocumentFileDownload downloadAndRecord(
            ExportedDocumentFile file, GeneratedDocument document) {
        DocumentFileDownload download = downloadRetainedFile(file);
        activityService.record(
                DocumentActivityType.DOCUMENT_VERSION_DOWNLOADED,
                document,
                document.isActive() ? "CURRENT_VERSION" : "PREVIOUS_VERSION",
                LocalDateTime.now());
        return download;
    }

    private DocumentFileDownload downloadRetainedFile(ExportedDocumentFile file) {
        if (file.getStorageStatus() != ObjectStorageStatus.AVAILABLE) {
            throw ResourceNotFoundException.documentFileNotFound();
        }
        byte[] content = objectStorage.get(file.getStorageKey());
        String actualSha256 = ObjectIntegrity.sha256(content);
        if (content.length != file.getContentSize()
                || !actualSha256.equals(file.getContentSha256())) {
            quarantine(file);
            log.error("Document object integrity verification failed");
            throw new ObjectStorageException("Document object failed integrity verification");
        }
        try {
            fileValidator.validateStored(file.getFileType(), content);
        } catch (IllegalArgumentException exception) {
            quarantine(file);
            log.error("Document object safety validation failed");
            throw new ObjectStorageException("Document object failed safety validation");
        }
        metrics.recordPayload(
                "file",
                "retrieved",
                null,
                file.getFileType(),
                content.length);
        return new DocumentFileDownload(
                fileValidator.safeFileName(file.getId(), file.getFileType()),
                fileValidator.canonicalMimeType(file.getFileType()),
                content);
    }

    private void quarantine(ExportedDocumentFile file) {
        file.setActive(false);
        file.setStorageStatus(ObjectStorageStatus.UNAVAILABLE);
        fileRepository.saveAndFlush(file);
    }

    private ExportedDocumentFile findActiveDocumentFile(String ownerId, UUID id) {
        ExportedDocumentFile file = fileRepository.findByIdAndGeneratedDocument_UserId(id, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        requireOwnedVisibleDocument(ownerId, file.getGeneratedDocumentId());
        if (!file.isActive() || file.getStorageStatus() != ObjectStorageStatus.AVAILABLE) {
            throw ResourceNotFoundException.documentFileNotFound();
        }
        return file;
    }

    private GeneratedDocument requireOwnedDownloadableDocument(
            String ownerId, UUID generatedDocumentId) {
        var document = documentRepository.findByIdAndUserId(generatedDocumentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentFileNotFound);
        if (document.getRetentionState() != DocumentRetentionState.AVAILABLE
                && document.getRetentionState() != DocumentRetentionState.ARCHIVED) {
            throw ResourceNotFoundException.documentFileNotFound();
        }
        return document;
    }

    public List<DocumentFileResponse> getFilesForDocument(String ownerId, UUID generatedDocumentId) {
        return metrics.observe("file", "list_export", null, null, () -> {
            requireOwnedVisibleDocument(ownerId, generatedDocumentId);
            return fileRepository
                    .findByGeneratedDocumentIdAndGeneratedDocument_UserIdOrderByCreatedAtDesc(
                            generatedDocumentId,
                            ownerId)
                    .stream()
                    .map(this::mapToResponse)
                    .toList();
        });
    }

    public List<DocumentFileResponse> getLatestFilesForDocument(
            String ownerId,
            UUID generatedDocumentId) {
        return metrics.observe("file", "list_export", null, null, () -> {
            requireOwnedVisibleDocument(ownerId, generatedDocumentId);
            return fileRepository
                    .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndActiveTrueOrderByUpdatedAtDesc(
                            generatedDocumentId,
                            ownerId)
                    .stream()
                    .map(this::mapToResponse)
                    .toList();
        });
    }

    @Transactional
    public DocumentFileResponse activateFileVersion(String ownerId, UUID fileId) {
        return metrics.observe(
                "file",
                "activate",
                null,
                null,
                () -> activateFileVersionInternal(ownerId, fileId));
    }

    private DocumentFileResponse activateFileVersionInternal(
            String ownerId, UUID fileId) {
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
        boolean repairedMultipleActive = activeFiles.size() > 1;
        activeFiles.forEach(file -> file.setActive(false));
        fileRepository.saveAllAndFlush(activeFiles);
        if (repairedMultipleActive) {
            metrics.recordReconciliation(
                    "file", "repaired", "multiple_active");
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
                metrics.recordReconciliation(
                        "file", "failure", "unexpected");
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
                .storedAt(withUtcOffset(file.getStoredAt()))
                .createdAt(withUtcOffset(file.getCreatedAt()))
                .updatedAt(withUtcOffset(file.getUpdatedAt()))
                .build();
    }

    private OffsetDateTime withUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

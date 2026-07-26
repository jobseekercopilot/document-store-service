package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileDownload;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
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

    @Transactional
    public DocumentFileResponse createDocumentFile(String ownerId, CreateDocumentFileRequest request) {
        long startedAt = System.nanoTime();
        requireOwnedDocument(ownerId, request.getGeneratedDocumentId());
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
                content);
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
            FileSource source) {
        long startedAt = System.nanoTime();
        log.info("Document file upload received fileType={} source={} sizeBytes={}",
                fileType,
                source,
                file == null ? 0 : file.getSize());
        requireOwnedDocument(ownerId, generatedDocumentId);
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
                    content);
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
        if (!file.isActive() || file.getStorageStatus() != ObjectStorageStatus.AVAILABLE) {
            log.warn("Inactive or unavailable document file access rejected");
            throw ResourceNotFoundException.documentFileNotFound();
        }
        return file;
    }

    public List<DocumentFileResponse> getFilesForDocument(String ownerId, UUID generatedDocumentId) {
        requireOwnedDocument(ownerId, generatedDocumentId);
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
        requireOwnedDocument(ownerId, generatedDocumentId);
        return fileRepository
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndActiveTrueOrderByUpdatedAtDesc(
                        generatedDocumentId,
                        ownerId)
                .stream()
                .map(this::mapToResponse)
                .toList();
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
        fileRepository.saveAll(activeFiles);
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
            byte[] content) {
        int version = fileRepository
                .findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
                        generatedDocumentId, fileType)
                .map(ExportedDocumentFile::getVersion)
                .map(current -> current + 1)
                .orElse(1);
        UUID fileId = UUID.randomUUID();
        String fileName = fileValidator.safeFileName(fileId, fileType);
        String mimeType = fileValidator.canonicalMimeType(fileType);
        String storageKey = ObjectKeyFactory.forFile(generatedDocumentId, fileId, version);
        String sha256 = ObjectIntegrity.sha256(content);
        objectStorage.put(storageKey, content, mimeType, sha256);
        try {
            deactivateCurrentFile(ownerId, generatedDocumentId, fileType);
            return fileRepository.saveAndFlush(ExportedDocumentFile.builder()
                    .id(fileId)
                    .generatedDocumentId(generatedDocumentId)
                    .ownerId(ownerId)
                    .fileType(fileType)
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .source(source)
                    .active(true)
                    .version(version)
                    .storageKey(storageKey)
                    .contentSize(content.length)
                    .contentSha256(sha256)
                    .storageStatus(ObjectStorageStatus.AVAILABLE)
                    .build());
        } catch (RuntimeException exception) {
            try {
                objectStorage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                log.error("Document object compensation failed; DOC-08 reconciliation required");
            }
            throw exception;
        }
    }

    private void requireOwnedDocument(String ownerId, UUID generatedDocumentId) {
        documentRepository.findByIdAndUserId(generatedDocumentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
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

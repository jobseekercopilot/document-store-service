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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class DocumentFileService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileService.class);

    public static final String DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String PDF_MIME_TYPE = "application/pdf";

    private final ExportedDocumentFileRepository fileRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final DocumentObjectStorage objectStorage;

    @Transactional
    public DocumentFileResponse createDocumentFile(String ownerId, CreateDocumentFileRequest request) {
        long startedAt = System.nanoTime();
        requireOwnedDocument(ownerId, request.getGeneratedDocumentId());
        validateFileType(request.getFileType());
        validateFileNameAndMimeType(request.getFileName(), request.getMimeType(), request.getFileType());
        byte[] content = Base64.getDecoder().decode(request.getFileContentBase64());
        ExportedDocumentFile saved = storeMetadataAndObject(
                ownerId,
                request.getGeneratedDocumentId(),
                request.getFileType(),
                request.getFileName(),
                request.getMimeType(),
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
        validateFileType(fileType);
        if (source != FileSource.USER_UPLOADED) {
            throw new IllegalArgumentException("source must be USER_UPLOADED");
        }

        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        validateFileNameAndMimeType(fileName, file.getContentType(), fileType);
        try {
            if (fileType == FileType.DOCX && !hasDocxStructure(file)) {
                throw new IllegalArgumentException("Only .docx files can be uploaded.");
            }
            ExportedDocumentFile saved = storeMetadataAndObject(
                    ownerId,
                    generatedDocumentId,
                    fileType,
                    fileName,
                    mimeType(fileType),
                    source,
                    file.getBytes());
            log.info("Document file upload saved fileType={} source={} durationMs={}",
                    fileType,
                    source,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return mapToResponse(saved);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded file");
        }
    }

    private boolean hasDocxStructure(MultipartFile file) throws IOException {
        boolean hasContentTypes = false;
        boolean hasDocumentXml = false;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    hasContentTypes = true;
                }
                if ("word/document.xml".equals(entry.getName())) {
                    hasDocumentXml = true;
                }
                if (hasContentTypes && hasDocumentXml) {
                    return true;
                }
            }
        }
        return false;
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
        log.info("Document file loaded fileType={} source={} sizeBytes={} durationMs={}",
                file.getFileType(),
                file.getSource(),
                content.length,
                (System.nanoTime() - startedAt) / 1_000_000);
        return new DocumentFileDownload(file.getFileName(), file.getMimeType(), content);
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
            String fileName,
            String mimeType,
            FileSource source,
            byte[] content) {
        int version = fileRepository
                .findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
                        generatedDocumentId, fileType)
                .map(ExportedDocumentFile::getVersion)
                .map(current -> current + 1)
                .orElse(1);
        UUID fileId = UUID.randomUUID();
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

    private void validateFileType(FileType fileType) {
        if (fileType == null || (fileType != FileType.DOCX && fileType != FileType.PDF)) {
            throw new IllegalArgumentException("Only DOCX and PDF files are supported");
        }
    }

    private void validateFileNameAndMimeType(String fileName, String mimeType, FileType fileType) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith("." + fileType.name().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Uploaded file extension does not match " + fileType);
        }
        if (mimeType == null || mimeType.isBlank() || MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(mimeType)) {
            return;
        }
        if (!mimeType(fileType).equals(mimeType)) {
            throw new IllegalArgumentException("Uploaded file MIME type does not match " + fileType);
        }
    }

    private String mimeType(FileType fileType) {
        return switch (fileType) {
            case DOCX -> DOCX_MIME_TYPE;
            case PDF -> PDF_MIME_TYPE;
        };
    }

    private DocumentFileResponse mapToResponse(ExportedDocumentFile file) {
        return DocumentFileResponse.builder()
                .id(file.getId())
                .generatedDocumentId(file.getGeneratedDocumentId())
                .fileType(file.getFileType())
                .fileName(file.getFileName())
                .mimeType(file.getMimeType())
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

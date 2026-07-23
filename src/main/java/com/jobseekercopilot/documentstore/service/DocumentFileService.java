package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
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

    public DocumentFileResponse createDocumentFile(CreateDocumentFileRequest request) {
        long startedAt = System.nanoTime();
        if (!documentRepository.existsById(request.getGeneratedDocumentId())) {
            throw new ResourceNotFoundException("Document not found with id: " + request.getGeneratedDocumentId());
        }
        validateFileType(request.getFileType());
        validateFileNameAndMimeType(request.getFileName(), request.getMimeType(), request.getFileType());
        deactivateCurrentFile(request.getGeneratedDocumentId(), request.getFileType());

        ExportedDocumentFile saved = fileRepository.save(ExportedDocumentFile.builder()
                .generatedDocumentId(request.getGeneratedDocumentId())
                .fileType(request.getFileType())
                .fileName(request.getFileName())
                .mimeType(request.getMimeType())
                .source(FileSource.GENERATED)
                .active(true)
                .fileContent(Base64.getDecoder().decode(request.getFileContentBase64()))
                .build());
        log.info("Generated document file saved fileId={} generatedDocumentId={} fileType={} source={} durationMs={}",
                saved.getId(),
                saved.getGeneratedDocumentId(),
                saved.getFileType(),
                saved.getSource(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
    }

    public DocumentFileResponse uploadReplacementFile(UUID generatedDocumentId, MultipartFile file, FileType fileType,
                                                       FileSource source) {
        long startedAt = System.nanoTime();
        log.info("Document file upload received generatedDocumentId={} fileType={} source={} sizeBytes={}",
                generatedDocumentId,
                fileType,
                source,
                file == null ? 0 : file.getSize());
        if (!documentRepository.existsById(generatedDocumentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + generatedDocumentId);
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is required");
        }
        validateFileType(fileType);
        if (source != FileSource.USER_UPLOADED) {
            throw new IllegalArgumentException("source must be USER_UPLOADED");
        }

        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        validateFileNameAndMimeType(fileName, file.getContentType(), fileType);
        deactivateCurrentFile(generatedDocumentId, fileType);

        try {
            if (fileType == FileType.DOCX && !hasDocxStructure(file)) {
                throw new IllegalArgumentException("Only .docx files can be uploaded.");
            }
            ExportedDocumentFile saved = fileRepository.save(ExportedDocumentFile.builder()
                    .generatedDocumentId(generatedDocumentId)
                    .fileType(fileType)
                    .fileName(fileName)
                    .mimeType(mimeType(fileType))
                    .source(source)
                    .active(true)
                    .fileContent(file.getBytes())
                    .build());
            log.info("Document file upload saved fileId={} generatedDocumentId={} fileType={} source={} durationMs={}",
                    saved.getId(),
                    generatedDocumentId,
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

    public DocumentFileResponse getDocumentFileMetadata(UUID id) {
        return mapToResponse(getDocumentFile(id));
    }

    public ExportedDocumentFile getDocumentFile(UUID id) {
        long startedAt = System.nanoTime();
        ExportedDocumentFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document file not found with id: " + id));
        if (!file.isActive()) {
            log.warn("Inactive document file download rejected fileId={} generatedDocumentId={}",
                    id,
                    file.getGeneratedDocumentId());
            throw new ResourceNotFoundException("Document file is no longer active: " + id);
        }
        log.info("Document file loaded fileId={} generatedDocumentId={} fileType={} source={} sizeBytes={} durationMs={}",
                id,
                file.getGeneratedDocumentId(),
                file.getFileType(),
                file.getSource(),
                file.getFileContent() == null ? 0 : file.getFileContent().length,
                (System.nanoTime() - startedAt) / 1_000_000);
        return file;
    }

    public List<DocumentFileResponse> getFilesForDocument(UUID generatedDocumentId) {
        if (!documentRepository.existsById(generatedDocumentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + generatedDocumentId);
        }
        return fileRepository.findByGeneratedDocumentIdOrderByCreatedAtDesc(generatedDocumentId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public List<DocumentFileResponse> getLatestFilesForDocument(UUID generatedDocumentId) {
        if (!documentRepository.existsById(generatedDocumentId)) {
            throw new ResourceNotFoundException("Document not found with id: " + generatedDocumentId);
        }
        return fileRepository.findByGeneratedDocumentIdAndActiveTrueOrderByUpdatedAtDesc(generatedDocumentId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private void deactivateCurrentFile(UUID generatedDocumentId, FileType fileType) {
        List<ExportedDocumentFile> activeFiles =
                fileRepository.findByGeneratedDocumentIdAndFileTypeAndActiveTrue(generatedDocumentId, fileType);
        activeFiles.forEach(file -> file.setActive(false));
        fileRepository.saveAll(activeFiles);
        if (!activeFiles.isEmpty()) {
            log.info("Previous active document files deactivated generatedDocumentId={} fileType={} count={}",
                    generatedDocumentId,
                    fileType,
                    activeFiles.size());
        }
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
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }
}

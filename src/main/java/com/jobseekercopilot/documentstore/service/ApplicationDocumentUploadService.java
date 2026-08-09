package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.ApplicationDocumentUploadResponse;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import com.jobseekercopilot.documentstore.upload.DocumentTextExtractor;
import com.jobseekercopilot.documentstore.upload.ExtractedDocumentText;
import com.jobseekercopilot.documentstore.upload.MalwareScanResult;
import com.jobseekercopilot.documentstore.upload.MalwareScanVerdict;
import com.jobseekercopilot.documentstore.upload.MalwareScanner;
import com.jobseekercopilot.documentstore.upload.MalwareScannerUnavailableException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentUploadService {

    private final ApplicationDocumentUploadRepository repository;
    private final ApplicationDocumentUploadPersistence persistence;
    private final GeneratedDocumentRepository documentRepository;
    private final GeneratedDocumentService documentService;
    private final DocumentFileService fileService;
    private final DocumentFileValidator fileValidator;
    private final DocumentObjectStorage objectStorage;
    private final MalwareScanner malwareScanner;
    private final DocumentTextExtractor textExtractor;
    private final DocumentStoreMetrics metrics;
    private final ApplicationDocumentUploadPublisher publisher;

    public ApplicationDocumentUploadResponse upload(
            String ownerId,
            String jobId,
            String applicationId,
            DocumentType documentType,
            FileType fileType,
            MultipartFile file,
            String idempotencyKey) {
        return metrics.observe(
                "upload",
                "process_upload",
                documentType,
                fileType,
                () -> uploadInternal(
                        ownerId,
                        jobId,
                        applicationId,
                        documentType,
                        fileType,
                        file,
                        idempotencyKey));
    }

    private ApplicationDocumentUploadResponse uploadInternal(
            String ownerId,
            String jobId,
            String applicationId,
            DocumentType documentType,
            FileType fileType,
            MultipartFile file,
            String idempotencyKey) {
        requireContext(ownerId, jobId, applicationId, documentType, fileType);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is required.");
        }
        fileValidator.validateDeclaredSize(file.getSize());
        String originalFileName = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename();
        String declaredMimeType = file.getContentType();
        byte[] content = read(file);
        fileValidator.validateDeclaredSize(content.length);
        String originalSha256 = ObjectIntegrity.sha256(content);
        String requestSha256 = OperationFingerprint.sha256(
                jobId,
                applicationId,
                documentType,
                fileType,
                originalFileName,
                declaredMimeType,
                originalSha256);
        ApplicationDocumentUpload upload = persistence.createOrReplay(
                ownerId,
                idempotencyKey,
                requestSha256,
                jobId,
                applicationId,
                documentType,
                fileType,
                originalSha256,
                content.length);
        if (upload.getState().terminal()) {
            return response(upload);
        }
        var claimed = persistence.claimForProcessing(ownerId, upload.getId());
        if (claimed.isEmpty()) {
            return response(required(ownerId, upload.getId()));
        }
        process(claimed.orElseThrow(), originalFileName, declaredMimeType, content);
        return response(required(ownerId, upload.getId()));
    }

    public ApplicationDocumentUploadResponse get(
            String ownerId, UUID operationId) {
        return metrics.observe(
                "upload",
                "retrieve_upload",
                null,
                null,
                () -> response(required(ownerId, operationId)));
    }

    private void process(
            ApplicationDocumentUpload upload,
            String originalFileName,
            String declaredMimeType,
            byte[] content) {
        try {
            persistence.requireQuota(upload);
            String quarantineKey = upload.getQuarantineKey();
            if (quarantineKey == null) {
                quarantineKey = ObjectKeyFactory.forUploadQuarantine(upload.getId());
                objectStorage.put(
                        quarantineKey,
                        content,
                        "application/octet-stream",
                        upload.getOriginalSha256());
                upload.setQuarantineKey(quarantineKey);
            }
            transition(upload, ApplicationDocumentUploadState.QUARANTINED);

            fileValidator.validateApplicationUpload(
                    upload.getFileType(),
                    originalFileName,
                    declaredMimeType,
                    content);
            transition(upload, ApplicationDocumentUploadState.SCANNING);
            MalwareScanResult scan = malwareScanner.scan(content);
            upload.setScannerEngine(scan.engine());
            upload.setScannerVersion(scan.engineVersion());
            upload.setScannerSignatureAt(scan.signatureAt());
            if (scan.verdict() == MalwareScanVerdict.INFECTED) {
                reject(
                        upload,
                        "MALWARE_DETECTED",
                        "The uploaded file was rejected by the malware scanner.");
                cleanupQuarantine(upload);
                return;
            }
            transition(upload, ApplicationDocumentUploadState.SCANNED_CLEAN);

            transition(upload, ApplicationDocumentUploadState.EXTRACTING);
            ExtractedDocumentText extracted = textExtractor.extract(
                    upload.getFileType(), content);
            upload.setExtractedTextSha256(extracted.sha256());
            upload.setExtractionState(extracted.state());
            repository.saveAndFlush(upload);

            UUID familyId = documentRepository
                    .findFirstByApplicationIdAndDocumentTypeAndUserIdOrderByVersionDesc(
                            upload.getApplicationId(),
                            upload.getDocumentType(),
                            upload.getOwnerId())
                    .map(document -> {
                        if (!document.getJobId().equals(upload.getJobId())) {
                            throw new OperationConflictException(
                                    "Application document context does not match the canonical job.");
                        }
                        return document.getDocumentFamilyId();
                    })
                    .orElseGet(() -> UUID.nameUUIDFromBytes(
                            ("application-upload-family:"
                                    + upload.getOwnerId()
                                    + ":"
                                    + upload.getApplicationId()
                                    + ":"
                                    + upload.getDocumentType().name())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            GeneratedDocumentResponse document = documentService.createDocument(
                    upload.getOwnerId(),
                    CreateDocumentRequest.builder()
                            .userId(upload.getOwnerId())
                            .jobId(upload.getJobId())
                            .applicationId(upload.getApplicationId())
                            .documentFamilyId(familyId)
                            .documentType(upload.getDocumentType())
                            .title("Uploaded " + displayType(upload.getDocumentType()))
                            .content(extracted.text())
                            .active(false)
                            .originalFilename(originalFileName)
                            .sourceType(DocumentSourceType.UPLOADED)
                            .createdBy(upload.getOwnerId())
                            .build(),
                    derivedKey(upload, "document"));
            upload.setDocumentId(document.getId());
            repository.saveAndFlush(upload);

            DocumentFileResponse artifact = fileService.storeApplicationUploadArtifact(
                    upload.getOwnerId(),
                    document.getId(),
                    upload.getFileType(),
                    originalFileName,
                    declaredMimeType,
                    content,
                    derivedKey(upload, "artifact"));
            upload.setArtifactId(artifact.getId());
            repository.saveAndFlush(upload);

            ApplicationDocumentUpload readyUpload = publisher.publish(upload);
            cleanupQuarantine(readyUpload);
        } catch (MalwareScannerUnavailableException exception) {
            fail(
                    upload,
                    ApplicationDocumentUploadState.SCAN_UNAVAILABLE,
                    "SCANNER_UNAVAILABLE",
                    "Document security scanning is temporarily unavailable.");
            cleanupQuarantine(upload);
        } catch (OperationConflictException exception) {
            reject(
                    upload,
                    "UPLOAD_CONFLICT",
                    "The upload conflicts with an application document limit or context.");
            cleanupQuarantine(upload);
        } catch (IllegalArgumentException exception) {
            reject(
                    upload,
                    upload.getState() == ApplicationDocumentUploadState.EXTRACTING
                            ? "EXTRACTION_REJECTED"
                            : "VALIDATION_REJECTED",
                    upload.getState() == ApplicationDocumentUploadState.EXTRACTING
                            ? "The document could not be extracted safely."
                            : "The file did not pass document safety validation.");
            cleanupQuarantine(upload);
        } catch (ObjectStorageException exception) {
            fail(
                    upload,
                    ApplicationDocumentUploadState.FAILED,
                    "STORAGE_UNAVAILABLE",
                    "Document storage is temporarily unavailable.");
            cleanupQuarantine(upload);
        } catch (RuntimeException exception) {
            fail(
                    upload,
                    ApplicationDocumentUploadState.FAILED,
                    "PROCESSING_FAILED",
                    "Document processing could not be completed safely.");
            cleanupQuarantine(upload);
        }
    }

    private void transition(
            ApplicationDocumentUpload upload,
            ApplicationDocumentUploadState state) {
        upload.setState(state);
        repository.saveAndFlush(upload);
    }

    private void reject(
            ApplicationDocumentUpload upload,
            String code,
            String message) {
        fail(upload, ApplicationDocumentUploadState.REJECTED, code, message);
    }

    private void fail(
            ApplicationDocumentUpload upload,
            ApplicationDocumentUploadState state,
            String code,
            String message) {
        upload.setState(state);
        upload.setFailureCode(code);
        upload.setFailureMessage(message);
        repository.saveAndFlush(upload);
    }

    private void cleanupQuarantine(ApplicationDocumentUpload upload) {
        if (upload.getQuarantineKey() == null) {
            return;
        }
        try {
            objectStorage.delete(upload.getQuarantineKey());
            upload.setQuarantineKey(null);
            repository.saveAndFlush(upload);
        } catch (RuntimeException ignored) {
            metrics.recordReconciliation(
                    "upload", "failure", "delete_pending");
        }
    }

    private ApplicationDocumentUpload required(
            String ownerId, UUID operationId) {
        return repository.findByIdAndOwnerId(operationId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded file.");
        }
    }

    private void requireContext(
            String ownerId,
            String jobId,
            String applicationId,
            DocumentType documentType,
            FileType fileType) {
        if (ownerId == null
                || ownerId.isBlank()
                || jobId == null
                || jobId.isBlank()
                || applicationId == null
                || applicationId.isBlank()
                || documentType == null
                || fileType == null) {
            throw new IllegalArgumentException(
                    "Owner, job, application, document type and file type are required.");
        }
        if (jobId.length() > 255 || applicationId.length() > 255) {
            throw new IllegalArgumentException(
                    "Job or application context is invalid.");
        }
    }

    private String derivedKey(
            ApplicationDocumentUpload upload, String purpose) {
        return "application-upload:" + upload.getId() + ":" + purpose;
    }

    private String displayType(DocumentType type) {
        return type == DocumentType.CV ? "CV" : "cover letter";
    }

    private ApplicationDocumentUploadResponse response(
            ApplicationDocumentUpload upload) {
        return new ApplicationDocumentUploadResponse(
                upload.getId(),
                upload.getJobId(),
                upload.getApplicationId(),
                upload.getDocumentType(),
                upload.getFileType(),
                upload.getState(),
                upload.getOriginalSha256(),
                upload.getOriginalSize(),
                upload.getExtractedTextSha256(),
                upload.getExtractionState(),
                upload.getDocumentId(),
                upload.getArtifactId(),
                upload.getFailureCode(),
                upload.getFailureMessage(),
                withUtcOffset(upload.getCreatedAt()),
                withUtcOffset(upload.getUpdatedAt()));
    }

    private OffsetDateTime withUtcOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

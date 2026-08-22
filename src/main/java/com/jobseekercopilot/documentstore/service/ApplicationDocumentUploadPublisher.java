package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentUploadPublisher {

    private final ApplicationDocumentUploadRepository uploadRepository;
    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureGuard ownerErasureGuard;
    private final DocumentActivityService activityService;

    @Transactional
    public ApplicationDocumentUpload publish(ApplicationDocumentUpload requested) {
        ownerErasureGuard.requireWritable(requested.getOwnerId());
        var document = documentRepository
                .findByIdAndUserId(requested.getDocumentId(), requested.getOwnerId())
                .orElseThrow(() -> new OperationConflictException(
                        "Uploaded document draft is unavailable."));
        operationLock.acquire("document-family:"
                + OperationFingerprint.sha256(
                        requested.getOwnerId(), document.getDocumentFamilyId()));
        ApplicationDocumentUpload upload = uploadRepository
                .findByIdAndOwnerId(requested.getId(), requested.getOwnerId())
                .orElseThrow(() -> new OperationConflictException(
                        "Upload operation is unavailable."));
        document = documentRepository
                .findByIdAndUserId(upload.getDocumentId(), upload.getOwnerId())
                .orElseThrow(() -> new OperationConflictException(
                        "Uploaded document draft is unavailable."));
        var artifact = fileRepository
                .findByIdAndGeneratedDocumentIdAndOwnerIdAndGeneratedDocument_UserId(
                        upload.getArtifactId(),
                        document.getId(),
                        upload.getOwnerId(),
                        upload.getOwnerId())
                .orElseThrow(() -> new OperationConflictException(
                        "Uploaded document artifact is unavailable."));
        if (document.getSourceType() != DocumentSourceType.UPLOADED
                || document.getLifecycleState() != DocumentLifecycleState.DRAFT
                || artifact.getStorageStatus() != ObjectStorageStatus.AVAILABLE
                || !artifact.getContentSha256().equals(upload.getOriginalSha256())
                || artifact.getContentSize() != upload.getOriginalSize()
                || artifact.getFileType() != upload.getFileType()
                || upload.getExtractedTextSha256() == null
                || upload.getExtractionState() == null) {
            throw new OperationConflictException(
                    "Uploaded document publication evidence is incomplete.");
        }
        document.setOriginalArtifactId(artifact.getId());
        document.setOriginalFileType(artifact.getFileType());
        document.setOriginalContentSize(artifact.getContentSize());
        document.setOriginalContentSha256(artifact.getContentSha256());
        document.setExtractionState(upload.getExtractionState());
        document.setLifecycleState(DocumentLifecycleState.APPROVED);
        document.setApprovedAt(LocalDateTime.now());
        document.setApprovedBy(upload.getOwnerId());
        documentRepository.saveAndFlush(document);

        activityService.recordOnce(
                "document-uploaded:" + upload.getId(),
                DocumentActivityType.DOCUMENT_UPLOADED,
                document,
                "READY",
                LocalDateTime.now());

        upload.setState(ApplicationDocumentUploadState.READY);
        upload.setFailureCode(null);
        upload.setFailureMessage(null);
        return uploadRepository.saveAndFlush(upload);
    }
}

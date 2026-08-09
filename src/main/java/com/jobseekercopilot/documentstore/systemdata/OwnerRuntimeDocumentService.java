package com.jobseekercopilot.documentstore.systemdata;

import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentApplicationWorkflowCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentCurrentCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentTombstoneAssociationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.service.DocumentFileLifecycleService;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerRuntimeDocumentService {
    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;
    private final ApplicationDocumentUploadRepository uploadRepository;
    private final DocumentCurrentCommandRepository currentCommandRepository;
    private final DocumentApplicationWorkflowCommandRepository
            workflowCommandRepository;
    private final DocumentStorageOperationRepository storageOperationRepository;
    private final DocumentLifecycleEventRepository lifecycleEventRepository;
    private final DocumentActivityEventRepository activityEventRepository;
    private final DocumentTombstoneAssociationRepository
            tombstoneAssociationRepository;
    private final DocumentFileLifecycleService fileLifecycleService;
    private final DocumentObjectStorage objectStorage;

    public OwnerRuntimeDocumentService(
            GeneratedDocumentRepository documentRepository,
            ExportedDocumentFileRepository fileRepository,
            ApplicationDocumentUploadRepository uploadRepository,
            DocumentCurrentCommandRepository currentCommandRepository,
            DocumentApplicationWorkflowCommandRepository
                    workflowCommandRepository,
            DocumentStorageOperationRepository storageOperationRepository,
            DocumentLifecycleEventRepository lifecycleEventRepository,
            DocumentActivityEventRepository activityEventRepository,
            DocumentTombstoneAssociationRepository
                    tombstoneAssociationRepository,
            DocumentFileLifecycleService fileLifecycleService,
            DocumentObjectStorage objectStorage) {
        this.documentRepository = documentRepository;
        this.fileRepository = fileRepository;
        this.uploadRepository = uploadRepository;
        this.currentCommandRepository = currentCommandRepository;
        this.workflowCommandRepository = workflowCommandRepository;
        this.storageOperationRepository = storageOperationRepository;
        this.lifecycleEventRepository = lifecycleEventRepository;
        this.activityEventRepository = activityEventRepository;
        this.tombstoneAssociationRepository = tombstoneAssociationRepository;
        this.fileLifecycleService = fileLifecycleService;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public OwnerRuntimeDocumentSummary verify(UUID ownerId) {
        return summary(ownerId.toString());
    }

    @Transactional
    public OwnerRuntimeDocumentSummary reset(UUID ownerId) {
        String owner = ownerId.toString();
        OwnerRuntimeDocumentSummary before = summary(owner);
        List<GeneratedDocument> documents = documentRepository.findByUserId(owner);
        List<UUID> documentIds = documents.stream()
                .map(GeneratedDocument::getId)
                .toList();
        List<ApplicationDocumentUpload> uploads =
                uploadRepository.findByOwnerId(owner);

        for (ApplicationDocumentUpload upload : uploads) {
            if (upload.getQuarantineKey() != null) {
                objectStorage.delete(upload.getQuarantineKey());
            }
        }
        if (!documentIds.isEmpty()) {
            fileLifecycleService.deleteForDocuments(documentIds);
            tombstoneAssociationRepository.deleteByDocumentIdIn(documentIds);
        }
        uploadRepository.deleteByOwnerId(owner);
        currentCommandRepository.deleteByOwnerId(owner);
        workflowCommandRepository.deleteByOwnerId(owner);
        storageOperationRepository.deleteByOwnerId(owner);
        lifecycleEventRepository.deleteByOwnerId(owner);
        activityEventRepository.deleteByOwnerId(owner);
        documentRepository.deleteByUserId(owner);
        return before;
    }

    private OwnerRuntimeDocumentSummary summary(String ownerId) {
        List<UUID> documentIds = documentRepository.findByUserId(ownerId)
                .stream()
                .map(GeneratedDocument::getId)
                .toList();
        return new OwnerRuntimeDocumentSummary(
                documentIds.size(),
                Math.toIntExact(fileRepository.countByOwnerId(ownerId)),
                Math.toIntExact(uploadRepository.countByOwnerId(ownerId)),
                Math.toIntExact(currentCommandRepository.countByOwnerId(ownerId)
                        + workflowCommandRepository.countByOwnerId(ownerId)),
                Math.toIntExact(
                        storageOperationRepository.countByOwnerId(ownerId)),
                Math.toIntExact(lifecycleEventRepository.countByOwnerId(ownerId)
                        + activityEventRepository.countByOwnerId(ownerId)),
                documentIds.isEmpty()
                        ? 0
                        : Math.toIntExact(tombstoneAssociationRepository
                                .countByDocumentIdIn(documentIds)));
    }
}

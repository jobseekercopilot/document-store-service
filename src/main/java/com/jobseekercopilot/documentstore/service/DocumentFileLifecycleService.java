package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class DocumentFileLifecycleService {
    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentObjectStorage objectStorage;
    private final PlatformTransactionManager transactionManager;

    public int deleteForDocuments(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return 0;
        }
        List<ExportedDocumentFile> files =
                fileRepository.findByGeneratedDocumentIdIn(documentIds);
        if (files.isEmpty()) {
            return 0;
        }
        markDeletePending(files.stream().map(ExportedDocumentFile::getId).toList());
        for (ExportedDocumentFile file : files) {
            objectStorage.delete(file.getStorageKey());
        }
        fileRepository.deleteAllInBatch(files);
        return files.size();
    }

    private void markDeletePending(List<UUID> fileIds) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            List<ExportedDocumentFile> pendingFiles = fileRepository.findAllById(fileIds);
            for (ExportedDocumentFile file : pendingFiles) {
                file.setActive(false);
                file.setStorageStatus(ObjectStorageStatus.DELETE_PENDING);
                file.setDeletedAt(LocalDateTime.now());
            }
            fileRepository.saveAllAndFlush(pendingFiles);
        });
    }
}

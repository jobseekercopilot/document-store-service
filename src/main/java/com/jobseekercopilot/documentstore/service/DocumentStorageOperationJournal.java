package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.entity.DocumentStorageOperation;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentStorageOperationJournal {

    private final DocumentStorageOperationRepository operationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StorageOperationReservation prepare(
            String ownerId,
            UUID generatedDocumentId,
            FileType fileType,
            int proposedVersion,
            String operationKey,
            String requestSha256) {
        if (operationKey != null) {
            DocumentStorageOperation existing = operationRepository
                    .findByOwnerIdAndOperationKey(ownerId, operationKey)
                    .orElse(null);
            if (existing != null) {
                if (!requestSha256.equals(existing.getRequestSha256())) {
                    throw new OperationConflictException(
                            "Idempotency-Key was already used for a different document file operation.");
                }
                if (existing.getState() == StorageOperationState.COMMITTED) {
                    throw new OperationConflictException(
                            "Committed document file operation has no replayable metadata.");
                }
                if (existing.getState() == StorageOperationState.ROLLED_BACK) {
                    existing.setState(StorageOperationState.PREPARED);
                    operationRepository.saveAndFlush(existing);
                }
                return reservation(existing);
            }
        }

        UUID fileId = UUID.randomUUID();
        DocumentStorageOperation prepared = operationRepository.saveAndFlush(
                DocumentStorageOperation.builder()
                        .fileId(fileId)
                        .ownerId(ownerId)
                        .generatedDocumentId(generatedDocumentId)
                        .fileType(fileType)
                        .fileVersion(proposedVersion)
                        .storageKey(ObjectKeyFactory.forFile(
                                generatedDocumentId, fileId, proposedVersion))
                        .operationKey(operationKey)
                        .requestSha256(requestSha256)
                        .state(StorageOperationState.PREPARED)
                        .build());
        return reservation(prepared);
    }

    @Transactional(readOnly = true)
    public int highestReservedVersion(UUID generatedDocumentId, FileType fileType) {
        Integer highest = operationRepository.findHighestReservedVersion(
                generatedDocumentId, fileType);
        return highest == null ? 0 : highest;
    }

    @Transactional
    public void markCommitted(UUID fileId) {
        DocumentStorageOperation operation = operationRepository.findById(fileId)
                .orElseThrow(() -> new IllegalStateException(
                        "Prepared document storage operation is missing"));
        operation.setState(StorageOperationState.COMMITTED);
        operationRepository.save(operation);
    }

    private StorageOperationReservation reservation(DocumentStorageOperation operation) {
        return new StorageOperationReservation(
                operation.getFileId(),
                operation.getFileVersion(),
                operation.getStorageKey());
    }
}

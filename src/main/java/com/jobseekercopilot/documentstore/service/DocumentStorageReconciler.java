package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentStorageReconciliationProperties;
import com.jobseekercopilot.documentstore.entity.DocumentStorageReconciliationCursor;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageReconciliationCursorRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentStorageReconciler {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageReconciler.class);
    private static final String INVENTORY_CURSOR = "document-objects";
    private static final String DELETE_PENDING_CURSOR = "delete-pending-metadata";
    private static final String PREPARED_OPERATION_CURSOR = "prepared-storage-operations";
    private static final String AVAILABLE_METADATA_CURSOR = "available-metadata";
    private static final String RECONCILIATION_LOCK = "document-storage-reconciliation";

    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentStorageOperationRepository operationRepository;
    private final DocumentStorageReconciliationCursorRepository cursorRepository;
    private final DocumentObjectStorage objectStorage;
    private final DocumentFileValidator fileValidator;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureGuard ownerErasureGuard;
    private final DocumentStorageReconciliationProperties properties;
    private final DocumentStoreMetrics metrics;

    @Transactional
    public DocumentStorageReconciliationReport reconcile() {
        operationLock.acquire(RECONCILIATION_LOCK);
        LocalDateTime cutoff =
                LocalDateTime.now().minusSeconds(properties.getMinimumAgeSeconds());
        PageRequest page = PageRequest.of(0, properties.getBatchSize());
        MutableReport report = new MutableReport();

        reconcileDeletePending(cutoff, page, report);
        reconcilePreparedOperations(cutoff, page, report);
        inspectAvailableMetadata(cutoff, page, report);
        inspectObjectInventory(report);

        DocumentStorageReconciliationReport result = report.toReport();
        recordMetrics(result);
        log.info(
                "Document storage reconciliation completed "
                        + "deleteCompleted={} deleteFailed={} preparedCommitted={} "
                        + "preparedRolledBack={} preparedFailed={} inspected={} "
                        + "quarantined={} inspectionFailed={} unknownOrphans={} inventoryFailed={}",
                result.deletePendingCompleted(),
                result.deletePendingFailed(),
                result.preparedCommitted(),
                result.preparedRolledBack(),
                result.preparedFailed(),
                result.availableInspected(),
                result.metadataQuarantined(),
                result.metadataInspectionFailed(),
                result.unknownOrphansDetected(),
                result.inventoryFailed());
        return result;
    }

    private void reconcileDeletePending(
            LocalDateTime cutoff,
            PageRequest page,
            MutableReport report) {
        DocumentStorageReconciliationCursor cursor = cursor(DELETE_PENDING_CURSOR);
        var files = cursor.getAfterKey() == null
                ? fileRepository.findByStorageStatusAndUpdatedAtBeforeOrderByStorageKeyAsc(
                        ObjectStorageStatus.DELETE_PENDING, cutoff, page)
                : fileRepository
                        .findByStorageStatusAndUpdatedAtBeforeAndStorageKeyGreaterThanOrderByStorageKeyAsc(
                                ObjectStorageStatus.DELETE_PENDING,
                                cutoff,
                                cursor.getAfterKey(),
                                page);
        for (var file : files) {
            if (!acquireWritableOwner(file.getOwnerId())) {
                report.deletePendingFailed++;
                continue;
            }
            try {
                objectStorage.delete(file.getStorageKey());
                fileRepository.delete(file);
                report.deletePendingCompleted++;
            } catch (ObjectStorageException exception) {
                report.deletePendingFailed++;
            }
        }
        fileRepository.flush();
        advance(cursor, files.stream().map(ExportedDocumentFile::getStorageKey).toList());
    }

    private void reconcilePreparedOperations(
            LocalDateTime cutoff,
            PageRequest page,
            MutableReport report) {
        DocumentStorageReconciliationCursor cursor = cursor(PREPARED_OPERATION_CURSOR);
        var operations = cursor.getAfterKey() == null
                ? operationRepository.findByStateAndUpdatedAtBeforeOrderByStorageKeyAsc(
                        StorageOperationState.PREPARED, cutoff, page)
                : operationRepository
                        .findByStateAndUpdatedAtBeforeAndStorageKeyGreaterThanOrderByStorageKeyAsc(
                                StorageOperationState.PREPARED,
                                cutoff,
                                cursor.getAfterKey(),
                                page);
        java.util.ArrayList<com.jobseekercopilot.documentstore.entity.DocumentStorageOperation>
                changed = new java.util.ArrayList<>();
        for (var operation : operations) {
            if (!acquireWritableOwner(operation.getOwnerId())) {
                report.preparedFailed++;
                continue;
            }
            if (fileRepository.existsByStorageKey(operation.getStorageKey())) {
                operation.setState(StorageOperationState.COMMITTED);
                report.preparedCommitted++;
                changed.add(operation);
                continue;
            }
            try {
                objectStorage.delete(operation.getStorageKey());
                operation.setState(StorageOperationState.ROLLED_BACK);
                report.preparedRolledBack++;
                changed.add(operation);
            } catch (ObjectStorageException exception) {
                report.preparedFailed++;
            }
        }
        operationRepository.saveAll(changed);
        operationRepository.flush();
        advance(cursor, operations.stream()
                .map(operation -> operation.getStorageKey())
                .toList());
    }

    private void inspectAvailableMetadata(
            LocalDateTime cutoff,
            PageRequest page,
            MutableReport report) {
        DocumentStorageReconciliationCursor cursor = cursor(AVAILABLE_METADATA_CURSOR);
        var files = cursor.getAfterKey() == null
                ? fileRepository.findByStorageStatusAndUpdatedAtBeforeOrderByStorageKeyAsc(
                        ObjectStorageStatus.AVAILABLE, cutoff, page)
                : fileRepository
                        .findByStorageStatusAndUpdatedAtBeforeAndStorageKeyGreaterThanOrderByStorageKeyAsc(
                                ObjectStorageStatus.AVAILABLE,
                                cutoff,
                                cursor.getAfterKey(),
                                page);
        for (var file : files) {
            if (!acquireWritableOwner(file.getOwnerId())) {
                report.metadataInspectionFailed++;
                continue;
            }
            report.availableInspected++;
            final boolean exists;
            try {
                exists = objectStorage.exists(file.getStorageKey());
            } catch (ObjectStorageException exception) {
                report.metadataInspectionFailed++;
                continue;
            }
            if (!exists) {
                quarantine(file);
                report.metadataQuarantined++;
                continue;
            }
            try {
                byte[] content = objectStorage.get(file.getStorageKey());
                if (content.length != file.getContentSize()
                        || !ObjectIntegrity.sha256(content).equals(file.getContentSha256())) {
                    quarantine(file);
                    report.metadataQuarantined++;
                    continue;
                }
                fileValidator.validateStored(file.getFileType(), content);
            } catch (IllegalArgumentException exception) {
                quarantine(file);
                report.metadataQuarantined++;
            } catch (ObjectStorageException exception) {
                report.metadataInspectionFailed++;
            }
        }
        fileRepository.flush();
        advance(cursor, files.stream().map(ExportedDocumentFile::getStorageKey).toList());
    }

    private void inspectObjectInventory(MutableReport report) {
        DocumentStorageReconciliationCursor cursor = cursor(INVENTORY_CURSOR);
        try {
            var page = objectStorage.listKeys(
                    properties.getObjectPrefix(),
                    cursor.getAfterKey(),
                    properties.getBatchSize());
            for (String key : page.keys()) {
                if (!fileRepository.existsByStorageKey(key)
                        && !operationRepository.existsByStorageKeyAndState(
                                key, StorageOperationState.PREPARED)) {
                    report.unknownOrphansDetected++;
                }
            }
            cursor.setAfterKey(page.nextAfterKey());
            cursorRepository.save(cursor);
        } catch (ObjectStorageException exception) {
            report.inventoryFailed++;
        }
    }

    private DocumentStorageReconciliationCursor cursor(String name) {
        return cursorRepository
                .findById(name)
                .orElseGet(() -> DocumentStorageReconciliationCursor.builder()
                        .cursorName(name)
                        .build());
    }

    private void advance(DocumentStorageReconciliationCursor cursor, java.util.List<String> keys) {
        String nextAfterKey = keys.size() == properties.getBatchSize()
                ? keys.get(keys.size() - 1)
                : null;
        cursor.setAfterKey(nextAfterKey);
        cursorRepository.save(cursor);
    }

    private void quarantine(ExportedDocumentFile file) {
        file.setActive(false);
        file.setStorageStatus(ObjectStorageStatus.UNAVAILABLE);
        fileRepository.save(file);
    }

    private boolean acquireWritableOwner(String ownerId) {
        try {
            ownerErasureGuard.requireWritable(ownerId);
            return true;
        } catch (OperationConflictException revoked) {
            return false;
        }
    }

    private void recordMetrics(DocumentStorageReconciliationReport report) {
        record("success", "delete_pending", report.deletePendingCompleted());
        record("failure", "delete_pending", report.deletePendingFailed());
        record("repaired", "prepared_committed", report.preparedCommitted());
        record("repaired", "prepared_rolled_back", report.preparedRolledBack());
        record("failure", "prepared_failed", report.preparedFailed());
        record("success", "metadata_inspected", report.availableInspected());
        record("repaired", "metadata_quarantined", report.metadataQuarantined());
        record(
                "failure",
                "metadata_inspection_failed",
                report.metadataInspectionFailed());
        record("failure", "unknown_orphan", report.unknownOrphansDetected());
        record("failure", "inventory_failed", report.inventoryFailed());
    }

    private void record(String outcome, String reason, int count) {
        metrics.recordReconciliation("file", outcome, reason, count);
    }

    private static final class MutableReport {
        private int deletePendingCompleted;
        private int deletePendingFailed;
        private int preparedCommitted;
        private int preparedRolledBack;
        private int preparedFailed;
        private int availableInspected;
        private int metadataQuarantined;
        private int metadataInspectionFailed;
        private int unknownOrphansDetected;
        private int inventoryFailed;

        private DocumentStorageReconciliationReport toReport() {
            return new DocumentStorageReconciliationReport(
                    deletePendingCompleted,
                    deletePendingFailed,
                    preparedCommitted,
                    preparedRolledBack,
                    preparedFailed,
                    availableInspected,
                    metadataQuarantined,
                    metadataInspectionFailed,
                    unknownOrphansDetected,
                    inventoryFailed);
        }
    }
}

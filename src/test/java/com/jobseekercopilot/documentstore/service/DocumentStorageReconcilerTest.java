package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.config.DocumentStorageReconciliationProperties;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageReconciliationCursorRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectKeyPage;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class DocumentStorageReconcilerTest {

    @Test
    void transientInspectionFailureDoesNotQuarantineAvailableMetadata() {
        var files = mock(ExportedDocumentFileRepository.class);
        var operations = mock(DocumentStorageOperationRepository.class);
        var cursors = mock(DocumentStorageReconciliationCursorRepository.class);
        var storage = mock(DocumentObjectStorage.class);
        var validator = mock(DocumentFileValidator.class);
        var lock = mock(DocumentOperationLock.class);
        var erasureGuard = mock(DocumentOwnerErasureGuard.class);
        var properties = properties();
        String storageKey = "documents/private/files/file/v1";
        ExportedDocumentFile file = ExportedDocumentFile.builder()
                .id(UUID.randomUUID())
                .ownerId("reconciliation-owner")
                .storageKey(storageKey)
                .fileType(FileType.PDF)
                .storageStatus(ObjectStorageStatus.AVAILABLE)
                .active(true)
                .build();
        when(files.findByStorageStatusAndUpdatedAtBeforeOrderByStorageKeyAsc(
                        any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> invocation.getArgument(0)
                                == ObjectStorageStatus.AVAILABLE
                        ? List.of(file)
                        : List.of());
        when(operations.findByStateAndUpdatedAtBeforeOrderByStorageKeyAsc(
                        any(StorageOperationState.class), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(storage.exists(storageKey))
                .thenThrow(new ObjectStorageException("temporary outage"));
        when(cursors.findById(any())).thenReturn(Optional.empty());
        when(storage.listKeys("documents/", null, 50))
                .thenReturn(new ObjectKeyPage(List.of(), null));

        var registry = new SimpleMeterRegistry();
        var reconciler = new DocumentStorageReconciler(
                files,
                operations,
                cursors,
                storage,
                validator,
                lock,
                erasureGuard,
                properties,
                new DocumentStoreMetrics(registry));

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.metadataInspectionFailed()).isEqualTo(1);
        assertThat(report.metadataQuarantined()).isZero();
        assertThat(file.getStorageStatus()).isEqualTo(ObjectStorageStatus.AVAILABLE);
        assertThat(file.isActive()).isTrue();
        verify(files, never()).save(any());
        assertThat(registry.get(DocumentStoreMetrics.RECONCILIATION_COUNT)
                        .tags(
                                "resource", "file",
                                "outcome", "failure",
                                "reason", "metadata_inspection_failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    private DocumentStorageReconciliationProperties properties() {
        var properties = new DocumentStorageReconciliationProperties();
        properties.setMinimumAgeSeconds(0);
        properties.setBatchSize(50);
        properties.setObjectPrefix("documents/");
        return properties;
    }
}

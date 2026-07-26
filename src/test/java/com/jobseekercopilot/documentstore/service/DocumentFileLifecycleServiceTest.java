package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DocumentFileLifecycleServiceTest {
    @Test
    void storageFailureLeavesDurableDeletePendingMetadataForReconciliation() {
        var repository = mock(ExportedDocumentFileRepository.class);
        var storage = mock(DocumentObjectStorage.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID documentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ExportedDocumentFile file = ExportedDocumentFile.builder()
                .id(fileId)
                .generatedDocumentId(documentId)
                .storageKey("documents/a/files/b/v1")
                .storageStatus(ObjectStorageStatus.AVAILABLE)
                .active(true)
                .build();
        when(repository.findByGeneratedDocumentIdIn(List.of(documentId)))
                .thenReturn(List.of(file));
        when(repository.findAllById(List.of(fileId))).thenReturn(List.of(file));
        org.mockito.Mockito.doThrow(new ObjectStorageException("unavailable"))
                .when(storage)
                .delete(file.getStorageKey());

        var service = new DocumentFileLifecycleService(repository, storage, transactions);

        assertThatThrownBy(() -> service.deleteForDocuments(List.of(documentId)))
                .isInstanceOf(ObjectStorageException.class);
        assertThat(file.getStorageStatus()).isEqualTo(ObjectStorageStatus.DELETE_PENDING);
        assertThat(file.isActive()).isFalse();
        assertThat(file.getDeletedAt()).isNotNull();
        var order = inOrder(repository, storage);
        order.verify(repository).saveAllAndFlush(List.of(file));
        order.verify(storage).delete(file.getStorageKey());
        verify(repository, never()).deleteAllInBatch(any());
    }
}

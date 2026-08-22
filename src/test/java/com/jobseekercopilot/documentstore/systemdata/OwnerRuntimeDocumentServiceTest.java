package com.jobseekercopilot.documentstore.systemdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerRuntimeDocumentServiceTest {
    @Mock private GeneratedDocumentRepository documentRepository;
    @Mock private ExportedDocumentFileRepository fileRepository;
    @Mock private ApplicationDocumentUploadRepository uploadRepository;
    @Mock private DocumentCurrentCommandRepository currentCommandRepository;
    @Mock private DocumentApplicationWorkflowCommandRepository workflowCommandRepository;
    @Mock private DocumentStorageOperationRepository storageOperationRepository;
    @Mock private DocumentLifecycleEventRepository lifecycleEventRepository;
    @Mock private DocumentActivityEventRepository activityEventRepository;
    @Mock private DocumentTombstoneAssociationRepository tombstoneRepository;
    @Mock private DocumentFileLifecycleService fileLifecycleService;
    @Mock private DocumentObjectStorage objectStorage;

    private OwnerRuntimeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new OwnerRuntimeDocumentService(
                documentRepository,
                fileRepository,
                uploadRepository,
                currentCommandRepository,
                workflowCommandRepository,
                storageOperationRepository,
                lifecycleEventRepository,
                activityEventRepository,
                tombstoneRepository,
                fileLifecycleService,
                objectStorage);
    }

    @Test
    void removesEveryOwnerArtifactAndItsObjectsWithinTheExactOwnerBoundary() {
        UUID ownerId = UUID.randomUUID();
        String owner = ownerId.toString();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByUserId(owner)).thenReturn(List.of(
                GeneratedDocument.builder().id(documentId).userId(owner).build()));
        when(fileRepository.countByOwnerId(owner)).thenReturn(2L);
        when(uploadRepository.countByOwnerId(owner)).thenReturn(1L);
        when(currentCommandRepository.countByOwnerId(owner)).thenReturn(1L);
        when(workflowCommandRepository.countByOwnerId(owner)).thenReturn(1L);
        when(storageOperationRepository.countByOwnerId(owner)).thenReturn(1L);
        when(lifecycleEventRepository.countByOwnerId(owner)).thenReturn(1L);
        when(activityEventRepository.countByOwnerId(owner)).thenReturn(1L);
        when(tombstoneRepository.countByDocumentIdIn(List.of(documentId)))
                .thenReturn(1L);
        when(uploadRepository.findByOwnerId(owner)).thenReturn(List.of(
                ApplicationDocumentUpload.builder()
                        .ownerId(owner)
                        .quarantineKey("quarantine/runtime-owner")
                        .build()));

        OwnerRuntimeDocumentSummary summary = service.reset(ownerId);

        assertThat(summary.total()).isEqualTo(10);
        verify(objectStorage).delete("quarantine/runtime-owner");
        verify(fileLifecycleService).deleteForDocuments(List.of(documentId));
        verify(tombstoneRepository).deleteByDocumentIdIn(List.of(documentId));
        verify(uploadRepository).deleteByOwnerId(owner);
        verify(currentCommandRepository).deleteByOwnerId(owner);
        verify(workflowCommandRepository).deleteByOwnerId(owner);
        verify(storageOperationRepository).deleteByOwnerId(owner);
        verify(lifecycleEventRepository).deleteByOwnerId(owner);
        verify(activityEventRepository).deleteByOwnerId(owner);
        verify(documentRepository).deleteByUserId(owner);
        verify(documentRepository, never()).deleteByUserId("another-owner");
    }

    @Test
    void emptyOwnerCleanupIsIdempotentAndDoesNotTouchObjectStorage() {
        UUID ownerId = UUID.randomUUID();
        when(documentRepository.findByUserId(ownerId.toString()))
                .thenReturn(List.of());
        when(uploadRepository.findByOwnerId(ownerId.toString()))
                .thenReturn(List.of());

        assertThat(service.reset(ownerId).total()).isZero();
        assertThat(service.reset(ownerId).total()).isZero();

        verify(objectStorage, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verify(fileLifecycleService, never()).deleteForDocuments(
                org.mockito.ArgumentMatchers.anyList());
    }
}

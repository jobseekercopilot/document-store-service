package com.jobseekercopilot.documentstore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentAccountLifecycleServiceTest {

    @Mock
    private GeneratedDocumentRepository documentRepository;
    @Mock
    private GeneratedDocumentService documentService;
    @Mock
    private DocumentFileService fileService;
    @Mock
    private DocumentLifecycleEventRepository eventRepository;
    @Mock
    private DocumentRetentionService retentionService;

    @Test
    void accountDeletionUsesDoc09RecoveryAndKeepsLegalHoldDocuments() {
        UUID availableId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID heldId = UUID.fromString("20000000-0000-4000-8000-000000000002");
        UUID deletedId = UUID.fromString("30000000-0000-4000-8000-000000000003");
        GeneratedDocument available = document(availableId, DocumentRetentionState.AVAILABLE, false);
        GeneratedDocument held = document(heldId, DocumentRetentionState.AVAILABLE, true);
        GeneratedDocument deleted = document(deletedId, DocumentRetentionState.DELETED, false);
        when(documentRepository.findByUserId("owner-123"))
                .thenReturn(List.of(deleted, held, available));
        DocumentAccountLifecycleService service = new DocumentAccountLifecycleService(
                documentRepository,
                documentService,
                fileService,
                eventRepository,
                retentionService);

        var result = service.recoverablyDelete("owner-123", "operation-123");

        assertEquals(1, result.recoverablyDeleted());
        assertEquals(1, result.alreadyDeleted());
        assertEquals(1, result.legalHoldRetained());
        verify(retentionService).softDelete(
                "owner-123", availableId, "account-lifecycle:operation-123");
        verify(retentionService, never()).softDelete(
                "owner-123", heldId, "account-lifecycle:operation-123");
    }

    private GeneratedDocument document(
            UUID id, DocumentRetentionState retentionState, boolean legalHold) {
        GeneratedDocument document = new GeneratedDocument();
        document.setId(id);
        document.setRetentionState(retentionState);
        document.setLegalHold(legalHold);
        return document;
    }
}

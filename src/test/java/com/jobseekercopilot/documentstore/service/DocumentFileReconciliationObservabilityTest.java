package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentFileReconciliationObservabilityTest {

    @Mock
    private ExportedDocumentFileRepository fileRepository;

    @Mock
    private GeneratedDocumentRepository documentRepository;

    @Mock
    private DocumentObjectStorage objectStorage;

    @Mock
    private DocumentFileValidator fileValidator;

    @Mock
    private DocumentOperationLock operationLock;

    @Mock
    private DocumentStorageOperationJournal storageOperationJournal;

    private SimpleMeterRegistry registry;
    private DocumentFileService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new DocumentFileService(
                fileRepository,
                documentRepository,
                objectStorage,
                fileValidator,
                operationLock,
                new DocumentStoreMetrics(registry),
                storageOperationJournal);
    }

    @Test
    void storingFileRecordsRepairWhenMultipleActiveFilesAreFound() {
        String owner = "synthetic-owner";
        UUID documentId = UUID.randomUUID();
        ExportedDocumentFile firstActive = activePdf(documentId);
        ExportedDocumentFile secondActive = activePdf(documentId);
        byte[] replacementContent =
                "%PDF-1.4 synthetic".getBytes(StandardCharsets.UTF_8);

        when(documentRepository.findByIdAndUserId(documentId, owner))
                .thenReturn(Optional.of(GeneratedDocument.builder()
                        .id(documentId)
                        .userId(owner)
                        .build()));
        when(fileRepository
                        .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                                documentId, owner, FileType.PDF))
                .thenReturn(List.of(firstActive, secondActive));
        when(fileRepository
                        .findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
                                documentId, FileType.PDF))
                .thenReturn(Optional.empty());
        UUID reservedFileId = UUID.randomUUID();
        when(storageOperationJournal.highestReservedVersion(
                        documentId, FileType.PDF))
                .thenReturn(0);
        when(storageOperationJournal.prepare(
                        eq(owner),
                        eq(documentId),
                        eq(FileType.PDF),
                        eq(1),
                        isNull(),
                        anyString()))
                .thenReturn(new StorageOperationReservation(
                        reservedFileId,
                        1,
                        "documents/synthetic/files/"
                                + reservedFileId
                                + "/v1"));
        when(fileValidator.decodeAndValidateGenerated(
                        FileType.PDF,
                        "synthetic.pdf",
                        "application/pdf",
                        Base64.getEncoder().encodeToString(replacementContent)))
                .thenReturn(replacementContent);
        when(fileValidator.safeFileName(any(UUID.class), any(FileType.class)))
                .thenReturn("document-synthetic.pdf");
        when(fileValidator.canonicalMimeType(FileType.PDF))
                .thenReturn("application/pdf");
        when(fileRepository.saveAndFlush(any(ExportedDocumentFile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createDocumentFile(
                owner,
                CreateDocumentFileRequest.builder()
                        .generatedDocumentId(documentId)
                        .fileType(FileType.PDF)
                        .fileName("synthetic.pdf")
                        .mimeType("application/pdf")
                        .fileContentBase64(
                                Base64.getEncoder().encodeToString(replacementContent))
                        .build(),
                null);

        assertThat(firstActive.isActive()).isFalse();
        assertThat(secondActive.isActive()).isFalse();
        verify(fileRepository).saveAllAndFlush(List.of(firstActive, secondActive));
        assertThat(registry.get(DocumentStoreMetrics.RECONCILIATION_COUNT)
                        .tags(
                                "resource", "file",
                                "outcome", "repaired",
                                "reason", "multiple_active")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentStoreMetrics.PAYLOAD_SIZE)
                        .tags(
                                "resource", "file",
                                "direction", "stored",
                                "document_type", "unknown",
                                "file_type", "pdf")
                        .summary()
                        .totalAmount())
                .isEqualTo(replacementContent.length);
    }

    private ExportedDocumentFile activePdf(UUID documentId) {
        return ExportedDocumentFile.builder()
                .id(UUID.randomUUID())
                .generatedDocumentId(documentId)
                .ownerId("synthetic-owner")
                .fileType(FileType.PDF)
                .fileName("synthetic.pdf")
                .mimeType("application/pdf")
                .active(true)
                .storageKey("documents/synthetic/file")
                .contentSize(1)
                .contentSha256("0".repeat(64))
                .build();
    }
}

package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentStorageOperation;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageReconciliationCursorRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "document-store.reconciliation.enabled=false",
        "document-store.reconciliation.minimum-age-seconds=0",
        "document-store.reconciliation.batch-size=2"
})
class DocumentStorageReconcilerIntegrationTest {

    private static final String OWNER = "reconciliation-owner";
    private static final String PDF_MIME_TYPE = "application/pdf";

    @Autowired
    private GeneratedDocumentService documentService;

    @Autowired
    private DocumentFileService fileService;

    @Autowired
    private DocumentStorageReconciler reconciler;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentStorageOperationRepository operationRepository;

    @Autowired
    private DocumentStorageReconciliationCursorRepository cursorRepository;

    @Autowired
    private DocumentObjectStorage objectStorage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanState() {
        cursorRepository.deleteAllInBatch();
        fileRepository.deleteAllInBatch();
        operationRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
        String afterKey = null;
        do {
            var page = objectStorage.listKeys("documents/", afterKey, 100);
            page.keys().forEach(objectStorage::delete);
            afterKey = page.nextAfterKey();
        } while (afterKey != null);
    }

    @Test
    void successfulFileWriteCommitsDurableOperationJournal() {
        UUID documentId = createDocument("journal-success");

        var file = fileService.createDocumentFile(
                OWNER,
                fileRequest(documentId, TestDocumentFiles.validPdf()),
                "journal-success");

        assertThat(operationRepository.findById(file.getId()))
                .get()
                .extracting(DocumentStorageOperation::getState)
                .isEqualTo(StorageOperationState.COMMITTED);
        assertThat(operationRepository.findById(file.getId()).orElseThrow().getStorageKey())
                .isEqualTo(fileRepository.findById(file.getId()).orElseThrow().getStorageKey());
    }

    @Test
    void generatedHttpsLinkedPdfRemainsAvailableAfterStoredByteReconciliation() {
        UUID documentId = createDocument("linked-pdf-reconciliation-parent");
        var file = fileService.createDocumentFile(
                OWNER,
                fileRequest(
                        documentId,
                        TestDocumentFiles.pdfWithUriLinks(
                                "https://github.com/jobseekercopilot")),
                "linked-pdf-reconciliation-file");

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.availableInspected()).isEqualTo(1);
        assertThat(report.metadataQuarantined()).isZero();
        assertThat(fileRepository.findById(file.getId()))
                .get()
                .satisfies(metadata -> {
                    assertThat(metadata.getStorageStatus())
                            .isEqualTo(ObjectStorageStatus.AVAILABLE);
                    assertThat(metadata.isActive()).isTrue();
                });
    }

    @Test
    void preparedOrphanIsRemovedAndRolledBackIdempotently() {
        UUID documentId = createDocument("prepared-orphan-parent");
        UUID fileId = UUID.randomUUID();
        String key = "documents/" + documentId + "/files/" + fileId + "/v1";
        byte[] content = TestDocumentFiles.validPdf();
        objectStorage.put(key, content, PDF_MIME_TYPE, ObjectIntegrity.sha256(content));
        operationRepository.saveAndFlush(DocumentStorageOperation.builder()
                .fileId(fileId)
                .ownerId(OWNER)
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileVersion(1)
                .storageKey(key)
                .operationKey("prepared-orphan")
                .requestSha256("a".repeat(64))
                .state(StorageOperationState.PREPARED)
                .build());

        DocumentStorageReconciliationReport first = reconciler.reconcile();
        DocumentStorageReconciliationReport second = reconciler.reconcile();

        assertThat(first.preparedRolledBack()).isEqualTo(1);
        assertThat(second.preparedRolledBack()).isZero();
        assertThat(objectStorage.exists(key)).isFalse();
        assertThat(operationRepository.findById(fileId))
                .get()
                .extracting(DocumentStorageOperation::getState)
                .isEqualTo(StorageOperationState.ROLLED_BACK);
    }

    @Test
    void completesPendingDeleteAndQuarantinesMissingAvailableObject() {
        UUID pendingDocument = createDocument("pending-parent");
        UUID missingDocument = createDocument("missing-parent");
        var pending = fileService.createDocumentFile(
                OWNER,
                fileRequest(pendingDocument, TestDocumentFiles.validPdf()),
                "pending-file");
        var missing = fileService.createDocumentFile(
                OWNER,
                fileRequest(missingDocument, TestDocumentFiles.validPdf()),
                "missing-file");
        var pendingMetadata = fileRepository.findById(pending.getId()).orElseThrow();
        pendingMetadata.setActive(false);
        pendingMetadata.setStorageStatus(ObjectStorageStatus.DELETE_PENDING);
        fileRepository.saveAndFlush(pendingMetadata);
        var missingMetadata = fileRepository.findById(missing.getId()).orElseThrow();
        objectStorage.delete(missingMetadata.getStorageKey());

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.deletePendingCompleted()).isEqualTo(1);
        assertThat(report.metadataQuarantined()).isEqualTo(1);
        assertThat(fileRepository.findById(pending.getId())).isEmpty();
        assertThat(fileRepository.findById(missing.getId()))
                .get()
                .satisfies(file -> {
                    assertThat(file.getStorageStatus()).isEqualTo(ObjectStorageStatus.UNAVAILABLE);
                    assertThat(file.isActive()).isFalse();
                });
    }

    @Test
    void detectsUnknownOrphanWithoutDeletingOrLoggingItsIdentity() {
        String orphanKey = "documents/orphan/files/unknown/v1";
        byte[] content = TestDocumentFiles.validPdf();
        objectStorage.put(
                orphanKey, content, PDF_MIME_TYPE, ObjectIntegrity.sha256(content));

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.unknownOrphansDetected()).isEqualTo(1);
        assertThat(objectStorage.exists(orphanKey)).isTrue();
    }

    @Test
    void quarantinesAnObjectWhoseStoredChecksumNoLongerMatchesMetadata() {
        UUID documentId = createDocument("corrupt-object-parent");
        var file = fileService.createDocumentFile(
                OWNER,
                fileRequest(documentId, TestDocumentFiles.validPdf()),
                "corrupt-object-file");
        var metadata = fileRepository.findById(file.getId()).orElseThrow();
        byte[] replacement = "%PDF-1.4\ncorrupt replacement\n%%EOF"
                .getBytes(StandardCharsets.US_ASCII);
        objectStorage.delete(metadata.getStorageKey());
        objectStorage.put(
                metadata.getStorageKey(),
                replacement,
                PDF_MIME_TYPE,
                ObjectIntegrity.sha256(replacement));

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.metadataQuarantined()).isEqualTo(1);
        assertThat(fileRepository.findById(file.getId()))
                .get()
                .satisfies(quarantined -> {
                    assertThat(quarantined.getStorageStatus())
                            .isEqualTo(ObjectStorageStatus.UNAVAILABLE);
                    assertThat(quarantined.isActive()).isFalse();
                });
    }

    @Test
    void rollsBackAnObjectWhoseEnclosingDatabaseTransactionFails() {
        UUID documentId = createDocument("database-failure-parent");
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            fileService.createDocumentFile(
                    OWNER,
                    fileRequest(documentId, TestDocumentFiles.validPdf()),
                    "database-failure-file");
            throw new IllegalStateException("injected database transaction failure");
        })).isInstanceOf(IllegalStateException.class);

        DocumentStorageOperation prepared = operationRepository
                .findByOwnerIdAndOperationKey(OWNER, "database-failure-file")
                .orElseThrow();
        assertThat(prepared.getState()).isEqualTo(StorageOperationState.PREPARED);
        assertThat(fileRepository.findById(prepared.getFileId())).isEmpty();
        assertThat(objectStorage.exists(prepared.getStorageKey())).isTrue();

        DocumentStorageReconciliationReport report = reconciler.reconcile();

        assertThat(report.preparedRolledBack()).isEqualTo(1);
        assertThat(objectStorage.exists(prepared.getStorageKey())).isFalse();
        assertThat(operationRepository.findById(prepared.getFileId()))
                .get()
                .extracting(DocumentStorageOperation::getState)
                .isEqualTo(StorageOperationState.ROLLED_BACK);
    }

    @Test
    void boundedMetadataCursorEventuallyInspectsEveryEligibleFile() {
        List<UUID> fileIds = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> {
                    UUID documentId = createDocument("cursor-parent-" + index);
                    var file = fileService.createDocumentFile(
                            OWNER,
                            fileRequest(documentId, TestDocumentFiles.validPdf()),
                            "cursor-file-" + index);
                    var metadata = fileRepository.findById(file.getId()).orElseThrow();
                    objectStorage.delete(metadata.getStorageKey());
                    return file.getId();
                })
                .toList();

        DocumentStorageReconciliationReport first = reconciler.reconcile();
        DocumentStorageReconciliationReport second = reconciler.reconcile();

        assertThat(first.availableInspected()).isEqualTo(2);
        assertThat(second.availableInspected()).isEqualTo(1);
        assertThat(fileRepository.findAllById(fileIds))
                .allSatisfy(file -> {
                    assertThat(file.getStorageStatus()).isEqualTo(ObjectStorageStatus.UNAVAILABLE);
                    assertThat(file.isActive()).isFalse();
                });
    }

    @Test
    void concurrentWorkersResolveOnePreparedOperationOnlyOnce() throws Exception {
        UUID documentId = createDocument("concurrent-reconciliation-parent");
        UUID fileId = UUID.randomUUID();
        String key = "documents/" + documentId + "/files/" + fileId + "/v1";
        byte[] content = TestDocumentFiles.validPdf();
        objectStorage.put(key, content, PDF_MIME_TYPE, ObjectIntegrity.sha256(content));
        operationRepository.saveAndFlush(DocumentStorageOperation.builder()
                .fileId(fileId)
                .ownerId(OWNER)
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileVersion(1)
                .storageKey(key)
                .operationKey("concurrent-reconciliation")
                .requestSha256("b".repeat(64))
                .state(StorageOperationState.PREPARED)
                .build());

        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<DocumentStorageReconciliationReport> first =
                    workers.submit(() -> reconcileAfter(start));
            Future<DocumentStorageReconciliationReport> second =
                    workers.submit(() -> reconcileAfter(start));
            start.countDown();

            int rolledBack = first.get(15, TimeUnit.SECONDS).preparedRolledBack()
                    + second.get(15, TimeUnit.SECONDS).preparedRolledBack();

            assertThat(rolledBack).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
        assertThat(objectStorage.exists(key)).isFalse();
        assertThat(operationRepository.findById(fileId))
                .get()
                .extracting(DocumentStorageOperation::getState)
                .isEqualTo(StorageOperationState.ROLLED_BACK);
    }

    private UUID createDocument(String operationKey) {
        return documentService.createDocument(
                        OWNER,
                        CreateDocumentRequest.builder()
                                .userId(OWNER)
                                .jobId("reconciliation-job")
                                .documentType(DocumentType.CV)
                                .title("Synthetic reconciliation document")
                                .content("Synthetic content")
                                .sourceType(DocumentSourceType.UPLOADED)
                                .originalFilename("synthetic.docx")
                                .createdBy("test")
                                .build(),
                        operationKey)
                .getId();
    }

    private CreateDocumentFileRequest fileRequest(UUID documentId, byte[] content) {
        return CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileName("synthetic.pdf")
                .mimeType(PDF_MIME_TYPE)
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();
    }

    private DocumentStorageReconciliationReport reconcileAfter(CountDownLatch start)
            throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        return reconciler.reconcile();
    }
}

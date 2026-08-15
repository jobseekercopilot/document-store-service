package com.jobseekercopilot.documentstore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentActivityEvent;
import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.DocumentApplicationWorkflowCommand;
import com.jobseekercopilot.documentstore.entity.DocumentCurrentCommand;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleAction;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleEvent;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentStorageOperation;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentApplicationWorkflowCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentCurrentCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureScopeRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.DocumentPermanentErasureService;
import com.jobseekercopilot.documentstore.service.DocumentPermanentErasureTransaction;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import com.jobseekercopilot.documentstore.service.ApplicationDocumentUploadPersistence;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "document-store.retention.purge-enabled=true",
        "document-store.retention.permanent-erasure-enabled=true",
        "document-store.retention.permanent-erasure-write-fence-enabled=true",
        "document-store.retention.versioned-object-erasure-enabled=true",
        "document-store.retention.policy-version=test-approved-permanent-erasure-v1",
        "document-store.retention.backup-retention-policy-version=test-backups-1d-v1",
        "document-store.retention.erasure-fingerprint-key=test-owner-fingerprint-secret-key-0001",
        "document-store.retention.maximum-backup-retention-days=1",
        "document-store.retention.permanent-erasure-fixed-delay-ms=86400000"
})
@AutoConfigureMockMvc
class DocumentPermanentErasureIntegrationTest {

    private static final String OWNER = "permanent-erasure-owner";
    private static final String NEIGHBOUR = "permanent-erasure-neighbour";
    private static final String RETENTION_TOKEN =
            "test-only-retention-admin-token-32-bytes";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DocumentPermanentErasureService service;
    @Autowired private DocumentPermanentErasureTransaction erasureTransaction;
    @Autowired private DocumentRetentionProperties retentionProperties;
    @Autowired private GeneratedDocumentService generatedDocumentService;
    @Autowired private ApplicationDocumentUploadPersistence uploadPersistence;
    @Autowired private GeneratedDocumentRepository documentRepository;
    @Autowired private ExportedDocumentFileRepository fileRepository;
    @Autowired private DocumentStorageOperationRepository storageOperationRepository;
    @Autowired private ApplicationDocumentUploadRepository uploadRepository;
    @Autowired private DocumentLifecycleEventRepository lifecycleRepository;
    @Autowired private DocumentActivityEventRepository activityRepository;
    @Autowired private DocumentCurrentCommandRepository currentCommandRepository;
    @Autowired private DocumentApplicationWorkflowCommandRepository workflowRepository;
    @Autowired private DocumentOwnerErasureOperationRepository erasureRepository;
    @Autowired private DocumentOwnerErasureScopeRepository scopeRepository;

    @MockBean private DocumentObjectStorage objectStorage;

    @BeforeEach
    @AfterEach
    void clean() {
        scopeRepository.deleteAll();
        erasureRepository.deleteAll();
        fileRepository.deleteAll();
        lifecycleRepository.deleteAll();
        activityRepository.deleteAll();
        currentCommandRepository.deleteAll();
        workflowRepository.deleteAll();
        uploadRepository.deleteAll();
        storageOperationRepository.deleteAll();
        documentRepository.deleteAll();
        reset(objectStorage);
    }

    @Test
    void erasesExactLiveOwnerDataKeepsNeighbourAndTruthfullyWaitsForBackups()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        GeneratedDocument neighbour = deletedDocument(NEIGHBOUR);
        seedAllOwnerEvidence(owner);
        file(neighbour);
        UUID operationId = UUID.randomUUID();

        mockMvc.perform(put("/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .headers(retentionHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "documentIds", List.of(owner.getId()),
                                "approvalReference", "privacy-approval-123"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.schemaVersion")
                        .value("document-permanent-erasure.v1"))
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.objectScopeCount").value(2))
                .andExpect(jsonPath("$.liveDataErased").value(true))
                .andExpect(jsonPath("$.backupRetentionWindowElapsed").value(false))
                .andExpect(jsonPath("$.backupExpiryEvidenceRecorded").value(false))
                .andExpect(jsonPath("$.backupCopiesMayRemain").value(true))
                .andExpect(jsonPath("$.backupRetentionUntil").isNotEmpty())
                .andExpect(jsonPath("$.backupRetentionPolicyVersion")
                        .value("test-backups-1d-v1"))
                .andExpect(jsonPath("$.backupRetentionDays").value(1))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OWNER))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(owner.getId().toString()))));

        verify(objectStorage).permanentlyDeletePrefix(
                "documents/" + owner.getId() + "/");
        verify(objectStorage).permanentlyDeleteKey(
                ObjectKeyFactory.forUploadQuarantine(uploadId(owner)));
        assertOwnerDataErased(OWNER);
        assertThat(documentRepository.findByUserId(NEIGHBOUR))
                .extracting(GeneratedDocument::getId)
                .containsExactly(neighbour.getId());
        assertThat(fileRepository.countByOwnerId(NEIGHBOUR)).isEqualTo(1);
        assertThat(erasureRepository.findById(operationId).orElseThrow().getOwnerId())
                .isNull();
        assertThat(scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId))
                .hasSize(2);

        performBackupExpiryAttestation(operationId, OWNER, "aws-backup-report-early")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Backup expiry cannot be attested before the snapshotted recovery window ends."));

        performErasure(operationId, owner)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"))
                .andExpect(jsonPath("$.documentCount").value(1));
        assertThat(erasureRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .headers(retentionHeaders(NEIGHBOUR)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.status")
                        .value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.backupRetentionPending").value(1));

        var pending = erasureRepository.findById(operationId).orElseThrow();
        pending.setLiveDataErasedAt(LocalDateTime.now().minusDays(2));
        pending.setBackupRetentionUntil(LocalDateTime.now().minusDays(1));
        erasureRepository.saveAndFlush(pending);

        assertThat(service.reconcileBatch()).isZero();
        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);

        performBackupExpiryAttestation(operationId, OWNER, "aws-backup-report-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.backupRetentionWindowElapsed").value(true))
                .andExpect(jsonPath("$.backupExpiryEvidenceRecorded").value(true))
                .andExpect(jsonPath("$.backupCopiesMayRemain").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("aws-backup-report-001"))));
        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.COMPLETED);
        assertThat(scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId))
                .hasSize(2);

        performBackupExpiryAttestation(operationId, OWNER, "aws-backup-report-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        performBackupExpiryAttestation(operationId, OWNER, "different-report")
                .andExpect(status().isConflict());

        clearInvocations(objectStorage);
        performErasure(operationId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(objectStorage).permanentlyDeletePrefix(
                "documents/" + owner.getId() + "/");
        verify(objectStorage).permanentlyDeleteKey(
                ObjectKeyFactory.forUploadQuarantine(uploadId(owner)));
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void objectFailureLeavesDurableRetryableStateAndNeverDeletesDatabaseEarly()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        doThrow(new ObjectStorageException("synthetic version-list denial"))
                .when(objectStorage)
                .permanentlyDeletePrefix(anyString());

        performErasure(operationId, owner)
                .andExpect(status().isServiceUnavailable());

        assertThat(documentRepository.findById(owner.getId())).isPresent();
        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.OBJECT_ERASURE_PENDING);
        assertThat(erasureRepository.findById(operationId).orElseThrow().getOwnerId())
                .isEqualTo(OWNER);

        reset(objectStorage);
        performErasure(operationId, owner)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"));
        assertOwnerDataErased(OWNER);
    }

    @Test
    void exactScopeLegalHoldRecoveryAndAuthorityGuardsFailClosed() throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        GeneratedDocument neighbour = deletedDocument(NEIGHBOUR);
        UUID operationId = UUID.randomUUID();

        mockMvc.perform(put("/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                "test-only-document-producer-token-32-bytes")
                        .header(DocumentOwnerResolver.OWNER_HEADER, OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "documentIds", List.of(owner.getId()),
                                "approvalReference", "approval"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .headers(retentionHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "documentIds", List.of(neighbour.getId()),
                                "approvalReference", "approval"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Permanent erasure requires the exact current owner document set."));

        owner.setLegalHold(true);
        owner.setLegalHoldReference("legal-case");
        owner.setLegalHoldUpdatedAt(LocalDateTime.now());
        owner.setLegalHoldUpdatedBy("retention-admin");
        documentRepository.saveAndFlush(owner);
        performErasure(operationId, owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Permanent erasure is blocked by a legal hold."));

        assertThat(erasureRepository.count()).isZero();
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        assertThat(documentRepository.findById(neighbour.getId())).isPresent();
    }

    @Test
    void concurrentWriteIsRejectedFromTheDurableErasureRequestOnward()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        CountDownLatch objectErasureEntered = new CountDownLatch(1);
        CountDownLatch allowObjectErasure = new CountDownLatch(1);
        doAnswer(invocation -> {
                    objectErasureEntered.countDown();
                    if (!allowObjectErasure.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out releasing object erasure");
                    }
                    return 0;
                })
                .when(objectStorage)
                .permanentlyDeletePrefix("documents/" + owner.getId() + "/");

        CompletableFuture<?> erasure = CompletableFuture.supplyAsync(() ->
                service.startOrResume(
                        OWNER,
                        operationId,
                        new PermanentErasureRequest(
                                List.of(owner.getId()),
                                "privacy-approval-concurrent"),
                        "document_retention_admin"));

        assertThat(objectErasureEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> generatedDocumentService.createDocument(
                        OWNER,
                        CreateDocumentRequest.builder()
                                .userId(OWNER)
                                .jobId("late-job")
                                .documentType(DocumentType.CV)
                                .title("Late document")
                                .content("must never be stored")
                                .sourceType(DocumentSourceType.UPLOADED)
                                .build(),
                        "late-write-operation"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("revoked for permanent account erasure");

        allowObjectErasure.countDown();
        erasure.get(5, TimeUnit.SECONDS);
        assertOwnerDataErased(OWNER);
        assertThat(documentRepository.findByUserId(OWNER)).isEmpty();
    }

    @Test
    void concurrentReplayCreatesOneOperationAndErasesTheOwnerIdempotently()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        PermanentErasureRequest request = new PermanentErasureRequest(
                List.of(owner.getId()), "privacy-approval-concurrent-replay");
        CountDownLatch start = new CountDownLatch(1);

        var first = CompletableFuture.supplyAsync(() -> {
            await(start);
            return service.startOrResume(
                    OWNER, operationId, request, "document_retention_admin");
        });
        var second = CompletableFuture.supplyAsync(() -> {
            await(start);
            return service.startOrResume(
                    OWNER, operationId, request, "document_retention_admin");
        });
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertThat(second.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertThat(erasureRepository.count()).isEqualTo(1);
        assertThat(scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId))
                .hasSize(1);
        assertOwnerDataErased(OWNER);
    }

    @Test
    void liveEraseDeadlineUsesTheSnapshottedBackupMaximumAndPolicyDriftBlocksEvidence()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        PermanentErasureRequest request = new PermanentErasureRequest(
                List.of(owner.getId()), "privacy-approval-policy-snapshot");
        erasureTransaction.prepare(
                OWNER, operationId, request, "document_retention_admin");

        retentionProperties.setMaximumBackupRetentionDays(2);
        try {
            service.startOrResume(
                    OWNER, operationId, request, "document_retention_admin");
            var operation = erasureRepository.findById(operationId).orElseThrow();
            assertThat(operation.getBackupRetentionDays()).isEqualTo(1);
            assertThat(java.time.Duration.between(
                            operation.getLiveDataErasedAt(),
                            operation.getBackupRetentionUntil()))
                    .isEqualTo(java.time.Duration.ofDays(1));

            operation.setLiveDataErasedAt(LocalDateTime.now().minusDays(2));
            operation.setBackupRetentionUntil(LocalDateTime.now().minusDays(1));
            erasureRepository.saveAndFlush(operation);
            performBackupExpiryAttestation(
                            operationId, OWNER, "aws-backup-policy-drift")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("Backup expiry attestation does not match the snapshotted backup policy."));
        } finally {
            retentionProperties.setMaximumBackupRetentionDays(1);
        }
    }

    @Test
    void operatorJournalReplayAfterPreErasureDatabaseRestoreErasesAgain() {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        PermanentErasureRequest retainedRequest = new PermanentErasureRequest(
                List.of(owner.getId()), "privacy-approval-restore-replay");

        service.startOrResume(
                OWNER,
                operationId,
                retainedRequest,
                "document_retention_admin");
        assertOwnerDataErased(OWNER);

        scopeRepository.deleteAll();
        erasureRepository.deleteAll();
        documentRepository.saveAndFlush(owner);
        reset(objectStorage);

        service.startOrResume(
                OWNER,
                operationId,
                retainedRequest,
                "document_retention_admin");

        assertOwnerDataErased(OWNER);
        assertThat(erasureRepository.findById(operationId)).isPresent();
        assertThat(scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId))
                .extracting(scope -> scope.getStorageScope())
                .containsExactly("documents/" + owner.getId() + "/");
        verify(objectStorage).permanentlyDeletePrefix(
                "documents/" + owner.getId() + "/");
    }

    @Test
    void staleTimedOutUploadWorkerCannotRecreateObjectOrRowAfterErasure() {
        GeneratedDocument owner = deletedDocument(OWNER);
        byte[] content = "stale-upload-content".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        UUID uploadId = UUID.randomUUID();
        uploadRepository.saveAndFlush(ApplicationDocumentUpload.builder()
                .id(uploadId)
                .ownerId(OWNER)
                .idempotencyKey("timed-out-upload")
                .requestSha256("a".repeat(64))
                .jobId("synthetic-job")
                .applicationId("synthetic-application")
                .documentType(DocumentType.CV)
                .fileType(FileType.PDF)
                .state(ApplicationDocumentUploadState.FAILED)
                .originalSha256(ObjectIntegrity.sha256(content))
                .originalSize(content.length)
                .processingStartedAt(LocalDateTime.now().minusHours(1))
                .quarantineKey(ObjectKeyFactory.forUploadQuarantine(uploadId))
                .documentId(owner.getId())
                .failureCode("PROCESSING_TIMEOUT")
                .failureMessage("Timed out")
                .build());

        service.startOrResume(
                OWNER,
                UUID.randomUUID(),
                new PermanentErasureRequest(
                        List.of(owner.getId()), "privacy-approval-stale-upload"),
                "document_retention_admin");
        assertOwnerDataErased(OWNER);
        clearInvocations(objectStorage);

        assertThatThrownBy(() -> uploadPersistence.storeQuarantine(
                        OWNER, uploadId, content))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("revoked for permanent account erasure");
        assertThatThrownBy(() -> uploadPersistence.cleanupQuarantine(
                        OWNER,
                        uploadId,
                        ApplicationDocumentUploadState.FAILED,
                        null,
                        false))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("revoked for permanent account erasure");
        verify(objectStorage, never()).put(
                anyString(), any(byte[].class), anyString(), anyString());
        verify(objectStorage, never()).delete(anyString());
        assertThat(uploadRepository.findById(uploadId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions performErasure(
            UUID operationId, GeneratedDocument document) throws Exception {
        return mockMvc.perform(put(
                                "/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .headers(retentionHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "documentIds", List.of(document.getId()),
                                "approvalReference", "privacy-approval-123"))));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out starting concurrent erasure replay");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted starting concurrent erasure replay", exception);
        }
    }

    private org.springframework.test.web.servlet.ResultActions
            performBackupExpiryAttestation(
                    UUID operationId, String owner, String evidenceReference)
                    throws Exception {
        return mockMvc.perform(put(
                                "/internal/retention/v1/permanent-erasures/{operationId}/backup-expiry-attestation",
                                operationId)
                        .headers(retentionHeaders(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "evidenceReference", evidenceReference))));
    }

    private HttpHeaders retentionHeaders(String owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(DocumentServiceIdentityFilter.SERVICE_HEADER, RETENTION_TOKEN);
        headers.set(DocumentOwnerResolver.OWNER_HEADER, owner);
        return headers;
    }

    private GeneratedDocument deletedDocument(String owner) {
        LocalDateTime now = LocalDateTime.now();
        return documentRepository.saveAndFlush(GeneratedDocument.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .jobId("synthetic-job")
                .documentType(DocumentType.CV)
                .title("Synthetic CV")
                .content("synthetic content")
                .version(1)
                .active(false)
                .lifecycleState(DocumentLifecycleState.DRAFT)
                .retentionState(DocumentRetentionState.DELETED)
                .deletedAt(now.minusDays(3))
                .deletedBy("account-lifecycle:synthetic-operation")
                .purgeEligibleAt(now.minusDays(2))
                .sourceType(DocumentSourceType.GENERATED)
                .createdBy(owner)
                .build());
    }

    private void seedAllOwnerEvidence(GeneratedDocument document) {
        UUID uploadId = uploadId(document);
        file(document);
        storageOperationRepository.saveAndFlush(DocumentStorageOperation.builder()
                .fileId(UUID.randomUUID())
                .ownerId(document.getUserId())
                .generatedDocumentId(document.getId())
                .fileType(FileType.PDF)
                .fileVersion(2)
                .storageKey("documents/" + document.getId() + "/files/"
                        + UUID.randomUUID() + "/v2")
                .operationKey("synthetic-operation")
                .requestSha256("a".repeat(64))
                .state(StorageOperationState.COMMITTED)
                .build());
        uploadRepository.saveAndFlush(ApplicationDocumentUpload.builder()
                .id(uploadId)
                .ownerId(document.getUserId())
                .idempotencyKey("synthetic-upload")
                .requestSha256("b".repeat(64))
                .jobId("synthetic-job")
                .applicationId("synthetic-application")
                .documentType(DocumentType.CV)
                .fileType(FileType.PDF)
                .state(ApplicationDocumentUploadState.READY)
                .originalSha256("c".repeat(64))
                .originalSize(10)
                .quarantineKey(ObjectKeyFactory.forUploadQuarantine(uploadId))
                .documentId(document.getId())
                .build());
        LocalDateTime now = LocalDateTime.now();
        lifecycleRepository.saveAndFlush(DocumentLifecycleEvent.builder()
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .ownerId(document.getUserId())
                .action(DocumentLifecycleAction.SOFT_DELETED)
                .fromState(DocumentRetentionState.AVAILABLE)
                .toState(DocumentRetentionState.DELETED)
                .actorId("account-lifecycle")
                .policyVersion("test-policy")
                .occurredAt(now.minusDays(2))
                .retentionExpiresAt(now.plusDays(300))
                .build());
        activityRepository.saveAndFlush(DocumentActivityEvent.builder()
                .eventKey("synthetic-activity:" + document.getId())
                .ownerId(document.getUserId())
                .eventType(DocumentActivityType.DOCUMENT_VERSION_ARCHIVED)
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .documentType(DocumentType.CV)
                .sourceType(DocumentSourceType.GENERATED)
                .version(1)
                .result("ARCHIVED")
                .occurredAt(now.minusDays(2))
                .retentionExpiresAt(now.plusDays(300))
                .build());
        currentCommandRepository.saveAndFlush(DocumentCurrentCommand.builder()
                .ownerId(document.getUserId())
                .idempotencyKey("synthetic-current")
                .requestSha256("d".repeat(64))
                .documentFamilyId(document.getDocumentFamilyId())
                .currentDocumentId(document.getId())
                .currentVersion(1)
                .build());
        workflowRepository.saveAndFlush(DocumentApplicationWorkflowCommand.builder()
                .operationId(UUID.randomUUID())
                .ownerId(document.getUserId())
                .applicationId(UUID.randomUUID())
                .commandType("GENERATED_WITHDRAWAL")
                .requestSha256("e".repeat(64))
                .status("COMPLETED")
                .build());
    }

    private void file(GeneratedDocument document) {
        UUID fileId = UUID.randomUUID();
        fileRepository.saveAndFlush(ExportedDocumentFile.builder()
                .id(fileId)
                .generatedDocumentId(document.getId())
                .ownerId(document.getUserId())
                .fileType(FileType.PDF)
                .fileName("synthetic.pdf")
                .mimeType("application/pdf")
                .source(FileSource.GENERATED)
                .active(false)
                .version(1)
                .storageKey("documents/" + document.getId() + "/files/" + fileId + "/v1")
                .contentSize(10)
                .contentSha256("f".repeat(64))
                .storageStatus(ObjectStorageStatus.AVAILABLE)
                .build());
    }

    private UUID uploadId(GeneratedDocument document) {
        return UUID.nameUUIDFromBytes(
                ("upload:" + document.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertOwnerDataErased(String owner) {
        assertThat(documentRepository.findByUserId(owner)).isEmpty();
        assertThat(fileRepository.countByOwnerId(owner)).isZero();
        assertThat(storageOperationRepository.countByOwnerId(owner)).isZero();
        assertThat(uploadRepository.countByOwnerId(owner)).isZero();
        assertThat(lifecycleRepository.countByOwnerId(owner)).isZero();
        assertThat(activityRepository.countByOwnerId(owner)).isZero();
        assertThat(currentCommandRepository.countByOwnerId(owner)).isZero();
        assertThat(workflowRepository.countByOwnerId(owner)).isZero();
    }
}

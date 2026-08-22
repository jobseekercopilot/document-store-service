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
import com.jobseekercopilot.documentstore.dto.PermanentErasureStatus;
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
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureRestoreRequestRepository;
import com.jobseekercopilot.documentstore.repository.DocumentOwnerErasureScopeRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.DocumentPermanentErasureService;
import com.jobseekercopilot.documentstore.service.DocumentPermanentErasureTransaction;
import com.jobseekercopilot.documentstore.service.PermanentErasureRecoveryJournalCodec;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import com.jobseekercopilot.documentstore.service.ApplicationDocumentUploadPersistence;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournal;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournalKeys;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
        "document-store.retention.permanent-erasure-fixed-delay-ms=86400000",
        "document-store.permanent-erasure-journal.provider=filesystem",
        "document-store.permanent-erasure-journal.filesystem-root=target/test-permanent-erasure-journal"
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
    @Autowired private PermanentErasureRecoveryJournalCodec recoveryJournalCodec;
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
    @Autowired private DocumentOwnerErasureRestoreRequestRepository restoreRequestRepository;
    @Autowired private DocumentOwnerErasureScopeRepository scopeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private DocumentObjectStorage objectStorage;
    @SpyBean private PermanentErasureJournal recoveryJournal;

    @BeforeEach
    @AfterEach
    void clean() {
        restoreRequestRepository.deleteAll();
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
        reset(recoveryJournal);
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
                        .value("document-permanent-erasure.v2"))
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.objectScopeCount").value(2))
                .andExpect(jsonPath("$.recoveryJournalEvidenceRecorded").value(true))
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
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
        verify(objectStorage, never()).permanentlyDeleteKey(anyString());
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.status").value("READY"));

        retentionProperties.setErasureFingerprintKey(
                "replacement-fingerprint-secret-key-0001");
        retentionProperties.setErasureFingerprintPreviousKeys(
                "test-owner-fingerprint-secret-key-0001");
        try {
            mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                            .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                    RETENTION_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ready").value(true))
                    .andExpect(jsonPath("$.status").value("READY"));
            retentionProperties.setErasureFingerprintPreviousKeys("");
            mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                            .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                    RETENTION_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ready").value(false))
                    .andExpect(jsonPath("$.status").value("MISCONFIGURED"));
            mockMvc.perform(get(
                                    "/internal/retention/v1/permanent-erasures/{operationId}",
                                    operationId)
                            .headers(retentionHeaders(OWNER)))
                    .andExpect(status().isConflict());
        } finally {
            retentionProperties.setErasureFingerprintKey(
                    "test-owner-fingerprint-secret-key-0001");
            retentionProperties.setErasureFingerprintPreviousKeys("");
        }
    }

    @Test
    @ResourceLock("default-time-zone")
    void recoveryJournalTimestampUsesUtcWhenProcessTimezoneDoesNot() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Instant before = Instant.now().minusSeconds(1);
            GeneratedDocument document = deletedDocument(OWNER);
            UUID operationId = UUID.randomUUID();

            erasureTransaction.prepare(
                    OWNER,
                    operationId,
                    new PermanentErasureRequest(
                            List.of(document.getId()),
                            "privacy-approval-non-utc-process"),
                    "document_retention_admin");

            var record = erasureTransaction.recoveryJournalRecord(operationId);
            Instant after = Instant.now().plusSeconds(1);
            assertThat(record.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(record.createdAt().toInstant())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
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
    void journalFailureAndRestartRetryNeverEraseDataBeforeEvidenceIsBound()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        doThrow(new ObjectStorageException("synthetic ambiguous journal write"))
                .when(recoveryJournal)
                .writeOrVerify(any(UUID.class), any(byte[].class), anyString());

        performErasure(operationId, owner)
                .andExpect(status().isServiceUnavailable());

        var pending = erasureRepository.findById(operationId).orElseThrow();
        assertThat(pending.getState())
                .isEqualTo(DocumentOwnerErasureState.JOURNAL_PENDING);
        assertThat(pending.getJournalContentSha256()).isNull();
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.recoveryJournalWritePending").value(1));

        reset(recoveryJournal);
        assertThat(service.reconcileBatch()).isEqualTo(1);
        assertOwnerDataErased(OWNER);
        assertThat(erasureRepository.findById(operationId).orElseThrow()
                        .getJournalContentSha256())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void crashAfterJournalWriteIsReconciledWithoutCreatingASecondRecord() {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        PermanentErasureRequest request = new PermanentErasureRequest(
                List.of(owner.getId()), "privacy-approval-crash-after-journal");
        erasureTransaction.prepare(
                OWNER, operationId, request, "document_retention_admin");
        var encoded = recoveryJournalCodec.encode(
                erasureTransaction.recoveryJournalRecord(operationId));
        var firstEvidence = recoveryJournal.writeOrVerify(
                operationId, encoded.content(), encoded.sha256());

        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.JOURNAL_PENDING);
        assertThat(documentRepository.findById(owner.getId())).isPresent();

        assertThat(service.reconcileBatch()).isEqualTo(1);

        var reconciled = erasureRepository.findById(operationId).orElseThrow();
        assertThat(reconciled.getJournalObjectVersion())
                .isEqualTo(firstEvidence.objectVersion());
        assertThat(reconciled.getState())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertOwnerDataErased(OWNER);
    }

    @Test
    void immutableJournalContentMismatchFailsClosedBeforeObjectOrDatabaseErase()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        PermanentErasureRequest request = new PermanentErasureRequest(
                List.of(owner.getId()), "privacy-approval-journal-collision");
        erasureTransaction.prepare(
                OWNER, operationId, request, "document_retention_admin");
        byte[] conflicting = "{\"schemaVersion\":\"conflicting\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        recoveryJournal.writeOrVerify(
                operationId,
                conflicting,
                recoveryJournalCodec.sha256(conflicting));

        assertThatThrownBy(() -> service.startOrResume(
                        OWNER, operationId, request, "document_retention_admin"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("collision");
        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.JOURNAL_PENDING);
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
    }

    @Test
    void restoreFailureIsDurableAndSuccessfulRetryRequiresFreshBackupEvidence()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        service.startOrResume(
                OWNER,
                operationId,
                new PermanentErasureRequest(
                        List.of(owner.getId()), "privacy-approval-restore-cycle"),
                "document_retention_admin");
        var originalPending = erasureRepository.findById(operationId).orElseThrow();
        originalPending.setLiveDataErasedAt(LocalDateTime.now().minusDays(2));
        originalPending.setBackupRetentionUntil(LocalDateTime.now().minusDays(1));
        erasureRepository.saveAndFlush(originalPending);
        performBackupExpiryAttestation(
                        operationId, OWNER, "original-backup-expiry-evidence")
                .andExpect(status().isOk());

        documentRepository.saveAndFlush(owner);
        clearInvocations(objectStorage);
        UUID restoreReplayId = UUID.randomUUID();
        doThrow(new ObjectStorageException("synthetic restore scope denial"))
                .when(objectStorage)
                .permanentlyDeletePrefix("documents/" + owner.getId() + "/");

        performRestoreReplay(
                        operationId,
                        restoreReplayId,
                        OWNER,
                        "restore-runbook-incident-001")
                .andExpect(status().isServiceUnavailable());

        var restorePending = erasureRepository.findById(operationId).orElseThrow();
        assertThat(restorePending.getState())
                .isEqualTo(DocumentOwnerErasureState.RESTORE_REPLAY_PENDING);
        assertThat(restorePending.getCompletedAt()).isNull();
        assertThat(restorePending.getBackupExpiryEvidenceSha256()).isNull();
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoreReplayPending").value(1));

        reset(objectStorage);
        assertThat(service.reconcileBatch()).isEqualTo(1);
        var freshBackup = erasureRepository.findById(operationId).orElseThrow();
        assertThat(freshBackup.getState())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertThat(freshBackup.getRestoreReplayObjectErasedAt()).isNotNull();
        assertThat(freshBackup.getBackupExpiryEvidenceSha256()).isNull();
        assertThat(java.time.Duration.between(
                        freshBackup.getLiveDataErasedAt(),
                        freshBackup.getBackupRetentionUntil()))
                .isEqualTo(java.time.Duration.ofDays(1));
        assertOwnerDataErased(OWNER);

        performBackupExpiryAttestation(
                        operationId, OWNER, "fresh-backup-evidence-too-early")
                .andExpect(status().isConflict());
        freshBackup.setLiveDataErasedAt(LocalDateTime.now().minusDays(2));
        freshBackup.setRestoreReplayRequestedAt(LocalDateTime.now().minusDays(3));
        freshBackup.setRestoreReplayObjectErasedAt(
                freshBackup.getLiveDataErasedAt());
        freshBackup.setBackupRetentionUntil(LocalDateTime.now().minusDays(1));
        erasureRepository.saveAndFlush(freshBackup);
        performBackupExpiryAttestation(
                        operationId, OWNER, "fresh-backup-expiry-evidence")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        clearInvocations(objectStorage);
        performRestoreReplay(
                        operationId,
                        restoreReplayId,
                        OWNER,
                        "restore-runbook-incident-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
        performRestoreReplay(
                        operationId,
                        restoreReplayId,
                        OWNER,
                        "different-restore-runbook-evidence")
                .andExpect(status().isConflict());
        performRestoreReplay(
                        operationId,
                        restoreReplayId,
                        NEIGHBOUR,
                        "restore-runbook-incident-001")
                .andExpect(status().isNotFound());
        mockMvc.perform(put(
                                "/internal/retention/v1/permanent-erasures/{operationId}/restore-replays/{restoreReplayId}",
                                operationId,
                                UUID.randomUUID())
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                "test-only-document-producer-token-32-bytes")
                        .header(DocumentOwnerResolver.OWNER_HEADER, OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "evidenceReference", "restore-runbook"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void journalReadFailureLeavesDurableRestoreObligationForSchedulerRetry()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        UUID operationId = UUID.randomUUID();
        service.startOrResume(
                OWNER,
                operationId,
                new PermanentErasureRequest(
                        List.of(owner.getId()), "privacy-approval-journal-read-retry"),
                "document_retention_admin");
        var originalPending = erasureRepository.findById(operationId).orElseThrow();
        originalPending.setLiveDataErasedAt(LocalDateTime.now().minusDays(2));
        originalPending.setBackupRetentionUntil(LocalDateTime.now().minusDays(1));
        erasureRepository.saveAndFlush(originalPending);
        performBackupExpiryAttestation(
                        operationId, OWNER, "original-journal-read-backup-evidence")
                .andExpect(status().isOk());

        documentRepository.saveAndFlush(owner);
        clearInvocations(objectStorage);
        UUID restoreReplayId = UUID.randomUUID();
        doThrow(new ObjectStorageException("synthetic recovery-journal read outage"))
                .when(recoveryJournal)
                .read(any(UUID.class), anyString());

        performRestoreReplay(
                        operationId,
                        restoreReplayId,
                        OWNER,
                        "restore-runbook-journal-read-outage")
                .andExpect(status().isServiceUnavailable());

        assertThat(restoreRequestRepository.count()).isEqualTo(1);
        assertThat(erasureRepository.findById(operationId).orElseThrow().getState())
                .isEqualTo(DocumentOwnerErasureState.COMPLETED);
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.status")
                        .value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.restoreJournalReadPending").value(1))
                .andExpect(jsonPath("$.restoreReplayPending").value(0));
        mockMvc.perform(get(
                                "/internal/retention/v1/permanent-erasures/{operationId}",
                                operationId)
                        .headers(retentionHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("RESTORE_JOURNAL_READ_PENDING"))
                .andExpect(jsonPath("$.liveDataErased").value(false))
                .andExpect(jsonPath("$.backupRetentionWindowElapsed").value(false))
                .andExpect(jsonPath("$.backupExpiryEvidenceRecorded").value(false))
                .andExpect(jsonPath("$.backupCopiesMayRemain").value(true))
                .andExpect(jsonPath("$.liveDataErasedAt")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.backupRetentionUntil")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.completedAt")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.restoreReplayId")
                        .value(restoreReplayId.toString()))
                .andExpect(jsonPath("$.restoreReplayEvidenceRecorded").value(true))
                .andExpect(jsonPath("$.restoreReplayRequestedAt").isNotEmpty())
                .andExpect(jsonPath("$.restoreReplayObjectErasedAt")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OWNER))));

        LocalDateTime staleAttempt = LocalDateTime.now(ZoneOffset.UTC).minusDays(2);
        jdbcTemplate.update(
                "UPDATE document_owner_erasure_restore_requests SET requested_at = ?, updated_at = ? WHERE restore_replay_id = ?",
                staleAttempt,
                staleAttempt,
                restoreReplayId);
        assertThat(service.reconcileBatch()).isZero();
        assertThat(restoreRequestRepository.findById(restoreReplayId)
                        .orElseThrow()
                        .getUpdatedAt())
                .isAfter(staleAttempt);
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());

        reset(recoveryJournal);
        assertThat(service.reconcileBatch()).isEqualTo(1);

        assertThat(restoreRequestRepository.count()).isZero();
        var freshBackup = erasureRepository.findById(operationId).orElseThrow();
        assertThat(freshBackup.getState())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertThat(freshBackup.getRestoreReplayId()).isEqualTo(restoreReplayId);
        assertThat(freshBackup.getRestoreReplayObjectErasedAt()).isNotNull();
        assertThat(freshBackup.getBackupExpiryEvidenceSha256()).isNull();
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
                .isEqualTo(PermanentErasureStatus.BACKUP_RETENTION_PENDING);
        assertThat(second.get(10, TimeUnit.SECONDS).status())
                .isEqualTo(PermanentErasureStatus.BACKUP_RETENTION_PENDING);
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
    void externalJournalReplayAfterPreErasureDatabaseRestoreReconstructsAndErasesAgain()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        GeneratedDocument neighbour = deletedDocument(NEIGHBOUR);
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

        performRestoreReplay(
                        operationId,
                        UUID.randomUUID(),
                        NEIGHBOUR,
                        "restore-runbook-wrong-owner")
                .andExpect(status().isNotFound());
        assertThat(restoreRequestRepository.count()).isZero();
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());

        performRestoreReplay(
                        operationId,
                        UUID.randomUUID(),
                        OWNER,
                        "restore-runbook-pre-erasure-backup")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"))
                .andExpect(jsonPath("$.restoreReplayEvidenceRecorded").value(true))
                .andExpect(jsonPath("$.restoreReplayObjectErasedAt").isNotEmpty())
                .andExpect(jsonPath("$.backupExpiryEvidenceRecorded").value(false));

        assertOwnerDataErased(OWNER);
        assertThat(documentRepository.findById(neighbour.getId())).isPresent();
        assertThat(erasureRepository.findById(operationId)).isPresent();
        assertThat(scopeRepository.findByOperationIdOrderByStorageScopeAsc(operationId))
                .extracting(scope -> scope.getStorageScope())
                .containsExactly("documents/" + owner.getId() + "/");
        verify(objectStorage).permanentlyDeletePrefix(
                "documents/" + owner.getId() + "/");
    }

    @Test
    void crashAfterDurableRestoreRequestIsSchedulerRecoveredFromOlderDatabaseState()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        GeneratedDocument neighbour = deletedDocument(NEIGHBOUR);
        UUID operationId = UUID.randomUUID();
        service.startOrResume(
                OWNER,
                operationId,
                new PermanentErasureRequest(
                        List.of(owner.getId()), "privacy-approval-crashed-restore"),
                "document_retention_admin");

        scopeRepository.deleteAll();
        erasureRepository.deleteAll();
        documentRepository.saveAndFlush(owner);
        clearInvocations(objectStorage);
        UUID restoreReplayId = UUID.randomUUID();

        // Simulate a process stop after the durable request transaction commits,
        // before the external recovery journal can be read.
        erasureTransaction.prepareRestoreReplay(
                OWNER,
                operationId,
                restoreReplayId,
                "restore-runbook-crash-before-journal-read",
                "document_retention_admin");

        assertThat(restoreRequestRepository.count()).isEqualTo(1);
        assertThat(erasureRepository.findById(operationId)).isEmpty();
        assertThat(documentRepository.findById(owner.getId())).isPresent();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());

        retentionProperties.setErasureFingerprintKey(
                "replacement-fingerprint-secret-key-0001");
        retentionProperties.setErasureFingerprintPreviousKeys("");
        try {
            mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                            .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                    RETENTION_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ready").value(false))
                    .andExpect(jsonPath("$.status").value("MISCONFIGURED"))
                    .andExpect(jsonPath("$.restoreJournalReadPending").value(1));
            assertThat(service.reconcileBatch()).isZero();
            assertThat(restoreRequestRepository.count()).isEqualTo(1);
            assertThat(documentRepository.findById(owner.getId())).isPresent();
        } finally {
            retentionProperties.setErasureFingerprintKey(
                    "test-owner-fingerprint-secret-key-0001");
            retentionProperties.setErasureFingerprintPreviousKeys("");
        }

        assertThat(service.reconcileBatch()).isEqualTo(1);

        assertThat(restoreRequestRepository.count()).isZero();
        var reconstructed = erasureRepository.findById(operationId).orElseThrow();
        assertThat(reconstructed.getState())
                .isEqualTo(DocumentOwnerErasureState.BACKUP_RETENTION_PENDING);
        assertThat(reconstructed.getRestoreReplayId()).isEqualTo(restoreReplayId);
        assertThat(reconstructed.getJournalContentSha256()).matches("[0-9a-f]{64}");
        assertOwnerDataErased(OWNER);
        assertThat(documentRepository.findById(neighbour.getId())).isPresent();
        verify(objectStorage).permanentlyDeletePrefix(
                "documents/" + owner.getId() + "/");
    }

    @Test
    void externalJournalRepairsLegacyOperationBeforeAnyReconciliationCanResume()
            throws Exception {
        GeneratedDocument owner = deletedDocument(OWNER);
        GeneratedDocument neighbour = deletedDocument(NEIGHBOUR);
        UUID operationId = UUID.randomUUID();
        service.startOrResume(
                OWNER,
                operationId,
                new PermanentErasureRequest(
                        List.of(owner.getId()), "privacy-approval-legacy-db-restore"),
                "document_retention_admin");

        var legacy = erasureRepository.findById(operationId).orElseThrow();
        legacy.setJournalRequired(false);
        legacy.setJournalSchemaVersion(null);
        legacy.setJournalObjectKey(null);
        legacy.setJournalObjectVersion(null);
        legacy.setJournalContentSha256(null);
        legacy.setJournalRecordedAt(null);
        erasureRepository.saveAndFlush(legacy);
        documentRepository.saveAndFlush(owner);
        clearInvocations(objectStorage);

        mockMvc.perform(get("/internal/retention/v1/permanent-erasures/readiness")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER,
                                RETENTION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.status")
                        .value("RECONCILIATION_REQUIRED"))
                .andExpect(jsonPath("$.recoveryJournalEvidenceMissing").value(1));
        assertThat(service.reconcileBatch()).isZero();
        verify(objectStorage, never()).permanentlyDeletePrefix(anyString());
        assertThat(documentRepository.findById(owner.getId())).isPresent();

        performRestoreReplay(
                        operationId,
                        UUID.randomUUID(),
                        OWNER,
                        "restore-runbook-legacy-operation")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status")
                        .value("BACKUP_RETENTION_PENDING"))
                .andExpect(jsonPath("$.recoveryJournalEvidenceRecorded")
                        .value(true));

        var repaired = erasureRepository.findById(operationId).orElseThrow();
        assertThat(repaired.isJournalRequired()).isTrue();
        assertThat(repaired.getJournalContentSha256()).matches("[0-9a-f]{64}");
        assertThat(repaired.getRestoreReplayObjectErasedAt()).isNotNull();
        assertOwnerDataErased(OWNER);
        assertThat(documentRepository.findById(neighbour.getId())).isPresent();
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

    private org.springframework.test.web.servlet.ResultActions performRestoreReplay(
            UUID operationId,
            UUID restoreReplayId,
            String owner,
            String evidenceReference) throws Exception {
        return mockMvc.perform(put(
                                "/internal/retention/v1/permanent-erasures/{operationId}/restore-replays/{restoreReplayId}",
                                operationId,
                                restoreReplayId)
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

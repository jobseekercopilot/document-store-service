package com.jobseekercopilot.documentstore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleAction;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleEvent;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentStorageOperation;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.DocumentRetentionMaintenanceService;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "document-store.retention.purge-enabled=true",
        "document-store.retention.maintenance-enabled=true",
        "document-store.retention.policy-version=test-approved-v1",
        "document-store.retention.recovery-days=1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class DocumentRetentionIntegrationTest {

    private static final String OWNER = "retention-owner";
    private static final String OTHER_OWNER = "other-retention-owner";
    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";
    private static final String RETENTION_TOKEN =
            "test-only-retention-admin-token-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentLifecycleEventRepository eventRepository;

    @Autowired
    private DocumentStorageOperationRepository operationRepository;

    @Autowired
    private DocumentRetentionMaintenanceService maintenanceService;

    @Autowired
    private DocumentObjectStorage objectStorage;

    @Test
    void archiveAndRestoreAreOwnerScopedIdempotentAndAudited() throws Exception {
        GeneratedDocument document = approvedDocument(null);
        UUID fileId = createPdf(document.getId());

        mockMvc.perform(patch("/api/v1/documents/{id}/archive", document.getId())
                        .headers(producerHeaders(OTHER_OWNER)))
                .andExpect(status().isNotFound());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/v1/documents/{id}/archive", document.getId())
                            .headers(producerHeaders(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retentionState").value("ARCHIVED"))
                    .andExpect(jsonPath("$.current").value(false))
                    .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        }

        assertThat(eventRepository.findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
                        document.getId(), OWNER))
                .extracting(DocumentLifecycleEvent::getAction)
                .containsExactly(DocumentLifecycleAction.ARCHIVED);

        mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/documents/{id}/reference", document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Document version is not available for application use."));
        mockMvc.perform(patch("/api/v1/documents/{id}/restore", document.getId())
                        .headers(producerHeaders(OTHER_OWNER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                                "/api/v1/documents/{id}/lifecycle-events",
                                document.getId())
                        .headers(producerHeaders(OTHER_OWNER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/documents/{id}", document.getId())
                        .headers(producerHeaders(OTHER_OWNER)))
                .andExpect(status().isNotFound());

        CreateDocumentFileRequest archivedFileRequest = CreateDocumentFileRequest.builder()
                .generatedDocumentId(document.getId())
                .fileType(FileType.PDF)
                .fileName("archived.pdf")
                .mimeType(MediaType.APPLICATION_PDF_VALUE)
                .fileContentBase64(Base64.getEncoder().encodeToString(
                        TestDocumentFiles.validPdf()))
                .build();
        mockMvc.perform(post("/api/v1/document-files")
                        .headers(producerHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(archivedFileRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Archived or deleted documents cannot accept file changes."));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/api/v1/documents/{id}/restore", document.getId())
                            .headers(producerHeaders(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retentionState").value("AVAILABLE"))
                    .andExpect(jsonPath("$.current").value(false))
                    .andExpect(jsonPath("$.archivedAt").doesNotExist());
        }

        mockMvc.perform(patch("/api/v1/documents/{id}/current", document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));

        mockMvc.perform(get(
                                "/api/v1/documents/{id}/lifecycle-events",
                                document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("ARCHIVED"))
                .andExpect(jsonPath("$[1].action").value("RESTORED"))
                .andExpect(jsonPath("$[0].policyVersion").value("test-approved-v1"));
    }

    @Test
    void softDeleteRetainsBytesForRecoveryAndRepeatedDeleteDoesNotDuplicateEvents()
            throws Exception {
        GeneratedDocument document = approvedDocument(null);
        UUID fileId = createPdf(document.getId());
        String storageKey = fileRepository.findById(fileId).orElseThrow().getStorageKey();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/v1/documents/{id}", document.getId())
                            .headers(producerHeaders(OWNER)))
                    .andExpect(status().isNoContent());
        }

        GeneratedDocument deleted =
                documentRepository.findById(document.getId()).orElseThrow();
        assertThat(deleted.getRetentionState()).isEqualTo(DocumentRetentionState.DELETED);
        assertThat(deleted.getPurgeEligibleAt()).isAfter(LocalDateTime.now());
        assertThat(deleted.isActive()).isFalse();
        assertThat(fileRepository.findById(fileId)).isPresent();
        assertThat(objectStorage.exists(storageKey)).isTrue();
        assertThat(eventRepository.findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
                        document.getId(), OWNER))
                .extracting(DocumentLifecycleEvent::getAction)
                .containsExactly(DocumentLifecycleAction.SOFT_DELETED);

        mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/documents/{id}/restore", document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionState").value("AVAILABLE"))
                .andExpect(jsonPath("$.purgeEligibleAt").doesNotExist());

        mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk());
    }

    @Test
    void purgeRequiresDedicatedAuthorityAndIsIrreversibleButRetrySafe()
            throws Exception {
        GeneratedDocument document = draftDocument(null);
        UUID fileId = createPdf(document.getId());
        String storageKey = fileRepository.findById(fileId).orElseThrow().getStorageKey();
        softDeleteAndExpire(document);

        mockMvc.perform(delete("/api/v1/documents/{id}/purge", document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/{id}/purge", document.getId())
                        .headers(retentionHeaders(OTHER_OWNER)))
                .andExpect(status().isNotFound());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/v1/documents/{id}/purge", document.getId())
                            .headers(retentionHeaders(OWNER)))
                    .andExpect(status().isNoContent());
        }

        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(fileRepository.findById(fileId)).isEmpty();
        assertThat(objectStorage.exists(storageKey)).isFalse();
        assertThat(eventRepository.existsByDocumentIdAndOwnerIdAndAction(
                        document.getId(), OWNER, DocumentLifecycleAction.PURGED))
                .isTrue();
    }

    @Test
    void legalHoldApplicationHistoryAndPreparedOperationsBlockDeletionOrPurge()
            throws Exception {
        GeneratedDocument held = draftDocument(null);
        mockMvc.perform(get("/api/v1/documents/{id}", held.getId())
                        .headers(retentionHeaders(OWNER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/documents/{id}", held.getId())
                        .headers(retentionHeaders(OWNER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/documents/{id}/legal-hold", held.getId())
                        .headers(producerHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":true,"reference":"support-case-123"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/documents/{id}/legal-hold", held.getId())
                        .headers(retentionHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":true,"reference":"support-case-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalHold").value(true));
        mockMvc.perform(delete("/api/v1/documents/{id}", held.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Document is protected by a legal hold and cannot be deleted."));
        mockMvc.perform(patch("/api/v1/documents/{id}/legal-hold", held.getId())
                        .headers(retentionHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false,"reference":"support-case-123-release"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalHold").value(false));
        mockMvc.perform(delete("/api/v1/documents/{id}", held.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isNoContent());
        assertThat(eventRepository.findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
                        held.getId(), OWNER))
                .extracting(DocumentLifecycleEvent::getAction)
                .containsExactly(
                        DocumentLifecycleAction.LEGAL_HOLD_APPLIED,
                        DocumentLifecycleAction.LEGAL_HOLD_RELEASED,
                        DocumentLifecycleAction.SOFT_DELETED);
        assertThat(eventRepository.findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
                        held.getId(), OWNER))
                .extracting(DocumentLifecycleEvent::getCaseReference)
                .containsExactly(
                        "support-case-123",
                        "support-case-123-release",
                        null);

        GeneratedDocument applicationLinked = draftDocument("application-history-1");
        softDeleteAndExpire(applicationLinked);
        mockMvc.perform(delete(
                                "/api/v1/documents/{id}/purge",
                                applicationLinked.getId())
                        .headers(retentionHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Application-linked document history cannot be purged."));

        GeneratedDocument unresolved = draftDocument(null);
        operationRepository.saveAndFlush(DocumentStorageOperation.builder()
                .fileId(UUID.randomUUID())
                .ownerId(OWNER)
                .generatedDocumentId(unresolved.getId())
                .fileType(FileType.PDF)
                .fileVersion(1)
                .storageKey("documents/unresolved/" + UUID.randomUUID())
                .requestSha256("a".repeat(64))
                .state(StorageOperationState.PREPARED)
                .build());
        softDeleteAndExpire(unresolved);
        mockMvc.perform(delete("/api/v1/documents/{id}/purge", unresolved.getId())
                        .headers(retentionHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Document has an unresolved storage operation and cannot be purged."));
    }

    @Test
    void maintenancePurgesOnlyExpiredCompletedJournalsAndLifecycleAudit()
            throws Exception {
        LocalDateTime old = LocalDateTime.now().minusDays(100);
        DocumentStorageOperation prepared =
                operation(StorageOperationState.PREPARED, old);
        DocumentStorageOperation committed =
                operation(StorageOperationState.COMMITTED, old);
        DocumentStorageOperation rolledBack =
                operation(StorageOperationState.ROLLED_BACK, old);
        operationRepository.saveAllAndFlush(
                java.util.List.of(prepared, committed, rolledBack));

        GeneratedDocument document = draftDocument(null);
        eventRepository.saveAndFlush(DocumentLifecycleEvent.builder()
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .ownerId(OWNER)
                .action(DocumentLifecycleAction.ARCHIVED)
                .fromState(DocumentRetentionState.AVAILABLE)
                .toState(DocumentRetentionState.ARCHIVED)
                .actorId(OWNER)
                .policyVersion("old-policy")
                .occurredAt(old)
                .retentionExpiresAt(LocalDateTime.now().minusSeconds(1))
                .build());

        var report = maintenanceService.purgeExpiredAuditHistory();

        assertThat(report.completedStorageOperationsPurged()).isEqualTo(2);
        assertThat(report.lifecycleEventsPurged()).isEqualTo(1);
        assertThat(operationRepository.findById(prepared.getFileId())).isPresent();
        assertThat(operationRepository.findById(committed.getFileId())).isEmpty();
        assertThat(operationRepository.findById(rolledBack.getFileId())).isEmpty();
        assertThat(eventRepository.count()).isZero();
    }

    private void softDeleteAndExpire(GeneratedDocument document) throws Exception {
        mockMvc.perform(delete("/api/v1/documents/{id}", document.getId())
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isNoContent());
        GeneratedDocument deleted =
                documentRepository.findById(document.getId()).orElseThrow();
        deleted.setPurgeEligibleAt(LocalDateTime.now().minusSeconds(1));
        documentRepository.saveAndFlush(deleted);
    }

    private UUID createPdf(UUID documentId) throws Exception {
        byte[] content = TestDocumentFiles.validPdf();
        CreateDocumentFileRequest request = CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileName("retained.pdf")
                .mimeType(MediaType.APPLICATION_PDF_VALUE)
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();
        String response = mockMvc.perform(post("/api/v1/document-files")
                        .headers(producerHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("id").asText());
    }

    private GeneratedDocument approvedDocument(String applicationId) {
        return documentRepository.saveAndFlush(GeneratedDocument.builder()
                .userId(OWNER)
                .jobId("retention-job")
                .applicationId(applicationId)
                .documentType(DocumentType.CV)
                .title("Approved retention document")
                .content("Synthetic approved retention content")
                .lifecycleState(DocumentLifecycleState.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(OWNER)
                .active(true)
                .build());
    }

    private GeneratedDocument draftDocument(String applicationId) {
        return documentRepository.saveAndFlush(GeneratedDocument.builder()
                .userId(OWNER)
                .jobId("retention-job")
                .applicationId(applicationId)
                .documentType(DocumentType.CV)
                .title("Draft retention document")
                .content("Synthetic draft retention content")
                .build());
    }

    private DocumentStorageOperation operation(
            StorageOperationState state, LocalDateTime updatedAt) {
        UUID fileId = UUID.randomUUID();
        return DocumentStorageOperation.builder()
                .fileId(fileId)
                .ownerId(OWNER)
                .generatedDocumentId(UUID.randomUUID())
                .fileType(FileType.PDF)
                .fileVersion(1)
                .storageKey("documents/maintenance/" + fileId)
                .requestSha256("b".repeat(64))
                .state(state)
                .createdAt(updatedAt.minusMinutes(1))
                .updatedAt(updatedAt)
                .build();
    }

    private org.springframework.http.HttpHeaders producerHeaders(String owner) {
        return serviceHeaders(PRODUCER_TOKEN, owner);
    }

    private org.springframework.http.HttpHeaders retentionHeaders(String owner) {
        return serviceHeaders(RETENTION_TOKEN, owner);
    }

    private org.springframework.http.HttpHeaders serviceHeaders(
            String token, String owner) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add(DocumentServiceIdentityFilter.SERVICE_HEADER, token);
        headers.add(DocumentOwnerResolver.OWNER_HEADER, owner);
        return headers;
    }
}

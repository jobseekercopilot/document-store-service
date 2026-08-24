package com.jobseekercopilot.documentstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.dto.GenerationMetadata;
import com.jobseekercopilot.documentstore.dto.ExpectedCurrentState;
import com.jobseekercopilot.documentstore.dto.DocumentApplicationAssociationSnapshot;
import com.jobseekercopilot.documentstore.dto.DocumentApplicationAssociationsSnapshot;
import com.jobseekercopilot.documentstore.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentApplicationAssociationState;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.DocumentCurrentCommandRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.repository.DocumentTombstoneAssociationRepository;
import com.jobseekercopilot.documentstore.service.DocumentFileService;
import com.jobseekercopilot.documentstore.service.DocumentFileValidator;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import com.jobseekercopilot.documentstore.service.DocumentFamilyHistoryService;
import com.jobseekercopilot.documentstore.service.DocumentRetentionService;
import com.jobseekercopilot.documentstore.service.ApplicationAssociationClient;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgresDocumentVersioningIntegrationTest {
    private static final String OWNER = "version-owner";
    private static final String APPLICATION = "version-application";
    private static final UUID DOCUMENT_FAMILY =
            UUID.fromString("80fa62d9-2f4f-43ea-9de7-2215538cfc42");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15.18-alpine3.23")
                    .withDatabaseName("document_store_versions")
                    .withUsername("document_store")
                    .withPassword("document_store");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add(
                "document-store.database.production-safety-check", () -> "false");
        registry.add("document-store.retention.purge-enabled", () -> "true");
        registry.add(
                "document-store.retention.policy-version",
                () -> "postgres-test-approved-v1");
    }

    @Autowired
    private GeneratedDocumentService documentService;

    @Autowired
    private DocumentFamilyHistoryService familyHistoryService;

    @Autowired
    private DocumentFileService fileService;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private DocumentTombstoneAssociationRepository tombstoneAssociationRepository;

    @Autowired
    private DocumentRetentionService retentionService;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentStorageOperationRepository storageOperationRepository;

    @Autowired
    private DocumentCurrentCommandRepository currentCommandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DocumentObjectStorage objectStorage;

    @MockBean
    private ApplicationAssociationClient applicationAssociationClient;

    @BeforeEach
    void cleanDatabase() {
        currentCommandRepository.deleteAllInBatch();
        fileRepository.deleteAllInBatch();
        storageOperationRepository.deleteAllInBatch();
        tombstoneAssociationRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
    }

    @Test
    void postgresMigrationPreservesScrubbedTombstoneAndFrozenAssociation() {
        GeneratedDocument document = documentRepository.saveAndFlush(
                GeneratedDocument.builder()
                        .userId(OWNER)
                        .jobId("tombstone-job")
                        .documentType(DocumentType.CV)
                        .title("Must be scrubbed")
                        .content("Sensitive content must be scrubbed")
                        .contentSha256("a".repeat(64))
                        .originalFilename("private-name.pdf")
                        .build());
        UUID applicationId = UUID.randomUUID();
        when(applicationAssociationClient.associations(
                        OWNER, document.getId()))
                .thenReturn(new DocumentApplicationAssociationsSnapshot(
                        document.getId(),
                        1,
                        List.of(new DocumentApplicationAssociationSnapshot(
                                applicationId,
                                DocumentType.CV,
                                DocumentApplicationAssociationState.FROZEN_USED,
                                "APPLIED",
                                LocalDateTime.now().minusDays(3)))));

        retentionService.softDelete(OWNER, document.getId(), OWNER);
        GeneratedDocument deleted = documentRepository.findById(document.getId())
                .orElseThrow();
        deleted.setPurgeEligibleAt(LocalDateTime.now().minusSeconds(1));
        documentRepository.saveAndFlush(deleted);
        retentionService.purge(OWNER, document.getId(), "retention-admin");

        GeneratedDocument tombstone = documentRepository.findById(document.getId())
                .orElseThrow();
        assertThat(tombstone.getRetentionState())
                .isEqualTo(DocumentRetentionState.PURGED);
        assertThat(tombstone.getContent()).isNull();
        assertThat(tombstone.getTitle()).isNull();
        assertThat(tombstone.getContentSha256()).isNull();
        assertThat(tombstone.getOriginalFilename()).isNull();
        assertThat(tombstoneAssociationRepository
                        .findByDocumentIdOrderByApplicationIdAsc(document.getId()))
                .singleElement()
                .extracting(association -> association.getApplicationId())
                .isEqualTo(applicationId);
    }

    @Test
    void concurrentDocumentCreatesAllocateUniqueFamilyVersionsAndApprovalLeavesCurrentUnset()
            throws Exception {
        List<GeneratedDocumentResponse> results = concurrently(
                8,
                index -> documentService.createDocument(
                        OWNER,
                        documentRequest("content-" + index),
                        "document-operation-" + index));

        assertThat(results)
                .extracting(GeneratedDocumentResponse::getVersion)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(documentRepository
                        .findByDocumentFamilyIdAndActiveTrueAndUserId(
                                DOCUMENT_FAMILY, OWNER))
                .isEmpty();

        results.stream()
                .sorted(java.util.Comparator.comparing(GeneratedDocumentResponse::getVersion))
                .forEach(result -> documentService.approveDocumentVersion(
                        OWNER, result.getId()));
        assertThat(documentRepository
                        .findByDocumentFamilyIdAndActiveTrueAndUserId(
                                DOCUMENT_FAMILY, OWNER))
                .isEmpty();
    }

    @Test
    void concurrentRetriesWithOneOperationKeyCreateOneVersion() throws Exception {
        CreateDocumentRequest request = documentRequest("one-logical-operation");
        List<GeneratedDocumentResponse> results = concurrently(
                8,
                ignored -> documentService.createDocument(
                        OWNER, request, "one-shared-retry-key"));

        assertThat(results)
                .extracting(GeneratedDocumentResponse::getId)
                .containsOnly(results.get(0).getId());
        assertThat(documentRepository.findByUserId(OWNER))
                .singleElement()
                .extracting(document -> document.getVersion())
                .isEqualTo(1);
    }

    @Test
    void concurrentCurrentCommandsWithOneExpectedPointerAllowOnlyOneWinner()
            throws Exception {
        List<GeneratedDocumentResponse> versions = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            GeneratedDocumentResponse version = documentService.createDocument(
                    OWNER,
                    documentRequest("current-content-" + index),
                    "current-document-" + index);
            versions.add(documentService.approveDocumentVersion(
                    OWNER, version.getId()));
        }
        familyHistoryService.selectCurrent(
                OWNER,
                DOCUMENT_FAMILY,
                new SelectFamilyCurrentRequest(
                        versions.get(0).getId(),
                        ExpectedCurrentState.NONE,
                        null),
                "initial-current");

        List<Boolean> outcomes = concurrently(2, index -> {
            try {
                familyHistoryService.selectCurrent(
                        OWNER,
                        DOCUMENT_FAMILY,
                        new SelectFamilyCurrentRequest(
                                versions.get(index + 1).getId(),
                                ExpectedCurrentState.SELECTED,
                                versions.get(0).getId()),
                        "competing-current-" + index);
                return true;
            } catch (OperationConflictException exception) {
                return false;
            }
        });

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(documentRepository
                        .findByDocumentFamilyIdAndActiveTrueAndUserId(
                                DOCUMENT_FAMILY, OWNER))
                .singleElement()
                .extracting(GeneratedDocument::getId)
                .isIn(versions.get(1).getId(), versions.get(2).getId());
    }

    @Test
    void concurrentFileCreatesAreUniqueRetrySafeAndRestorable() throws Exception {
        UUID documentId = documentService.createDocument(
                        OWNER,
                        documentRequest("file-parent"),
                        "file-parent-operation")
                .getId();
        byte[] content = TestDocumentFiles.validDocx();

        List<DocumentFileResponse> results = concurrently(
                6,
                index -> fileService.createDocumentFile(
                        OWNER,
                        fileRequest(documentId, content),
                        "file-operation-" + index));

        assertThat(results)
                .extracting(DocumentFileResponse::getVersion)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(fileRepository
                        .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                                documentId, OWNER, FileType.DOCX))
                .singleElement()
                .extracting(file -> file.getVersion())
                .isEqualTo(6);

        DocumentFileResponse first = results.stream()
                .filter(result -> result.getVersion() == 1)
                .findFirst()
                .orElseThrow();
        DocumentFileResponse restored = fileService.activateFileVersion(OWNER, first.getId());
        assertThat(restored.getVersion()).isEqualTo(1);
        assertThat(fileRepository
                        .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                                documentId, OWNER, FileType.DOCX))
                .singleElement()
                .extracting(file -> file.getId())
                .isEqualTo(first.getId());

        DocumentFileResponse second = results.stream()
                .filter(result -> result.getVersion() == 2)
                .findFirst()
                .orElseThrow();
        concurrently(
                2,
                index -> fileService.activateFileVersion(
                        OWNER, index == 0 ? first.getId() : second.getId()));
        assertThat(fileRepository
                        .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                                documentId, OWNER, FileType.DOCX))
                .hasSize(1);
    }

    @Test
    void idempotentRetriesReturnOriginalRecordsAndRejectKeyReuse() {
        CreateDocumentRequest documentRequest = documentRequest("retry-content");
        GeneratedDocumentResponse first =
                documentService.createDocument(OWNER, documentRequest, "retry-document");
        GeneratedDocumentResponse replay =
                documentService.createDocument(OWNER, documentRequest, "retry-document");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(documentRepository.findByUserId(OWNER)).hasSize(1);
        assertThatThrownBy(() -> documentService.createDocument(
                        OWNER, documentRequest("different-content"), "retry-document"))
                .isInstanceOf(OperationConflictException.class);
        assertThat(documentRepository.findByUserId(OWNER)).hasSize(1);

        byte[] firstPdf = TestDocumentFiles.validPdf();
        byte[] differentPdf =
                (new String(firstPdf, java.nio.charset.StandardCharsets.ISO_8859_1) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        DocumentFileResponse firstFile = fileService.createDocumentFile(
                OWNER, pdfRequest(first.getId(), firstPdf), "retry-file");
        DocumentFileResponse replayedFile = fileService.createDocumentFile(
                OWNER, pdfRequest(first.getId(), firstPdf), "retry-file");

        assertThat(replayedFile.getId()).isEqualTo(firstFile.getId());
        assertThatThrownBy(() -> fileService.createDocumentFile(
                        OWNER, pdfRequest(first.getId(), differentPdf), "retry-file"))
                .isInstanceOf(OperationConflictException.class);
        assertThat(fileRepository.findByGeneratedDocumentIdOrderByCreatedAtDesc(first.getId()))
                .hasSize(1);
    }

    @Test
    void failedReplacementRollsBackCurrentSelectionAndDatabaseEnforcesInvariants() {
        GeneratedDocumentResponse current = documentService.createDocument(
                OWNER, documentRequest("stable-current"), "stable-operation");
        current = documentService.approveDocumentVersion(OWNER, current.getId());
        current = documentService.selectCurrentDocumentVersion(OWNER, current.getId());
        UUID currentId = current.getId();
        CreateDocumentRequest invalid = documentRequest("invalid-next");
        invalid.setTitle(null);

        assertThatThrownBy(() ->
                        documentService.createDocument(OWNER, invalid, "invalid-operation"))
                .isInstanceOf(RuntimeException.class);
        assertThat(documentRepository
                        .findByApplicationIdAndDocumentTypeAndActiveTrueAndUserId(
                                APPLICATION, DocumentType.CV, OWNER))
                .singleElement()
                .extracting(document -> document.getId())
                .isEqualTo(currentId);

        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO generated_documents (
                            id, user_id, job_id, application_id, document_family_id, document_type,
                            title, content, version, active, current_slot,
                            lifecycle_state, source_type, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        OWNER,
                        "duplicate-job",
                        APPLICATION,
                        DOCUMENT_FAMILY,
                        "CV",
                        "Duplicate version",
                        "Must be rejected",
                        1,
                        false,
                        null,
                        "DRAFT",
                        "GENERATED",
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE generated_documents SET current_slot = NULL WHERE id = ?",
                        currentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        UPDATE generated_documents
                        SET operation_key = 'missing-fingerprint', request_sha256 = NULL
                        WHERE id = ?
                        """,
                        currentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreateDocumentRequest documentRequest(String content) {
        return CreateDocumentRequest.builder()
                .userId(OWNER)
                .jobId("version-job")
                .applicationId(APPLICATION)
                .documentFamilyId(DOCUMENT_FAMILY)
                .documentType(DocumentType.CV)
                .title("Versioned CV")
                .content(content)
                .generationMetadata(generationMetadata())
                .build();
    }

    private GenerationMetadata generationMetadata() {
        String hash = "a".repeat(64);
        return GenerationMetadata.builder()
                .releaseId("release-1")
                .bundleId("bundle-1")
                .bundleVersion("1.0.0")
                .bundleSha256(hash)
                .templateVersion("1.0.0")
                .templateSha256(hash)
                .rulesVersion("1.0.0")
                .rulesSha256(hash)
                .schemaId("cv")
                .schemaVersion("1.0.0")
                .schemaSha256(hash)
                .evaluationPolicyVersion("1.0.0")
                .evaluationPolicySha256(hash)
                .build();
    }

    private CreateDocumentFileRequest fileRequest(UUID documentId, byte[] content) {
        return CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.DOCX)
                .fileName("cv.docx")
                .mimeType(DocumentFileValidator.DOCX_MIME_TYPE)
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();
    }

    private CreateDocumentFileRequest pdfRequest(UUID documentId, byte[] content) {
        return CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileName("cv.pdf")
                .mimeType(DocumentFileValidator.PDF_MIME_TYPE)
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();
    }

    private <T> List<T> concurrently(int count, IntFunction<T> operation)
            throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                int operationIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return operation.apply(operationIndex);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

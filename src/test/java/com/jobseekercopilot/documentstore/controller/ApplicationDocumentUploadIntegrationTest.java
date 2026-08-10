package com.jobseekercopilot.documentstore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.ApplicationAssociationClient;
import com.jobseekercopilot.documentstore.service.ApplicationDocumentUploadService;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.upload.MalwareScanResult;
import com.jobseekercopilot.documentstore.upload.MalwareScanVerdict;
import com.jobseekercopilot.documentstore.upload.MalwareScanner;
import com.jobseekercopilot.documentstore.upload.MalwareScannerUnavailableException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApplicationDocumentUploadIntegrationTest {

    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationDocumentUploadRepository uploadRepository;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentActivityEventRepository activityRepository;

    @Autowired
    private DocumentObjectStorage objectStorage;

    @Autowired
    private DocumentUploadProperties uploadProperties;

    @Autowired
    private ApplicationDocumentUploadService uploadService;

    @MockBean
    private MalwareScanner malwareScanner;

    @MockBean
    private ApplicationAssociationClient applicationAssociationClient;

    @Test
    void cleanDocxBecomesOneApprovedImmutableVersionAndReplaysExactly()
            throws Exception {
        cleanScanner();
        String owner = "upload-owner-clean";
        String application = UUID.randomUUID().toString();
        byte[] bytes = TestDocumentFiles.validDocx();

        JsonNode first = upload(
                owner,
                application,
                "CV",
                "DOCX",
                "cv.docx",
                DOCX_MIME,
                bytes,
                "upload-clean-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.extractionState").value("SUCCEEDED"))
                .andReturnJson();
        JsonNode replay = upload(
                owner,
                application,
                "CV",
                "DOCX",
                "cv.docx",
                DOCX_MIME,
                bytes,
                "upload-clean-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andReturnJson();

        assertThat(replay.get("operationId").asText())
                .isEqualTo(first.get("operationId").asText());
        assertThat(replay.get("documentId").asText())
                .isEqualTo(first.get("documentId").asText());
        assertThat(replay.get("artifactId").asText())
                .isEqualTo(first.get("artifactId").asText());
        assertThat(uploadRepository.findAll())
                .filteredOn(upload -> owner.equals(upload.getOwnerId()))
                .hasSize(1);
        assertThat(documentRepository.findByUserId(owner)).hasSize(1);

        var document = documentRepository.findById(
                        UUID.fromString(first.get("documentId").asText()))
                .orElseThrow();
        var artifact = fileRepository.findById(
                        UUID.fromString(first.get("artifactId").asText()))
                .orElseThrow();
        assertThat(document.getLifecycleState())
                .isEqualTo(DocumentLifecycleState.APPROVED);
        assertThat(document.getSourceType()).isEqualTo(DocumentSourceType.UPLOADED);
        assertThat(document.getContent()).isEqualTo("Synthetic CV");
        assertThat(document.getContentSha256())
                .isEqualTo(first.get("extractedTextSha256").asText());
        assertThat(document.getOriginalContentSha256())
                .isEqualTo(first.get("originalSha256").asText());
        assertThat(document.getOriginalContentSha256())
                .isEqualTo(artifact.getContentSha256());
        assertThat(document.getOriginalArtifactId()).isEqualTo(artifact.getId());
        assertThat(document.getExtractionState())
                .isEqualTo(DocumentExtractionState.SUCCEEDED);
        assertThat(artifact.getStorageStatus()).isEqualTo(ObjectStorageStatus.AVAILABLE);
        assertThat(activityRepository.findAll())
                .filteredOn(event -> event.getDocumentId().equals(document.getId()))
                .extracting(event -> event.getEventType().name())
                .containsExactlyInAnyOrder(
                        "DOCUMENT_VERSION_CREATED", "DOCUMENT_UPLOADED");
        assertThat(uploadRepository.findById(
                        UUID.fromString(first.get("operationId").asText()))
                .orElseThrow()
                .getQuarantineKey())
                .isNull();
    }

    @Test
    void overlappingExactReplayCannotDuplicateOrOverwriteSuccessfulLifecycle()
            throws Exception {
        String owner = "upload-owner-concurrent";
        String application = UUID.randomUUID().toString();
        byte[] bytes = TestDocumentFiles.validDocx();
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch allowScan = new CountDownLatch(1);
        AtomicInteger scans = new AtomicInteger();
        when(malwareScanner.scan(any())).thenAnswer(invocation -> {
            scans.incrementAndGet();
            scanStarted.countDown();
            if (!allowScan.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to complete test scan");
            }
            return new MalwareScanResult(
                    MalwareScanVerdict.CLEAN,
                    "CLAMAV",
                    "test",
                    LocalDateTime.now());
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> uploadService.upload(
                    owner,
                    "job-123",
                    application,
                    com.jobseekercopilot.documentstore.entity.DocumentType.CV,
                    com.jobseekercopilot.documentstore.entity.FileType.DOCX,
                    new MockMultipartFile("file", "cv.docx", DOCX_MIME, bytes),
                    "concurrent-key"));
            assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var overlapping = uploadService.upload(
                    owner,
                    "job-123",
                    application,
                    com.jobseekercopilot.documentstore.entity.DocumentType.CV,
                    com.jobseekercopilot.documentstore.entity.FileType.DOCX,
                    new MockMultipartFile("file", "cv.docx", DOCX_MIME, bytes),
                    "concurrent-key");
            assertThat(overlapping.state())
                    .isIn(
                            ApplicationDocumentUploadState.QUARANTINED,
                            ApplicationDocumentUploadState.SCANNING);

            allowScan.countDown();
            var completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.state()).isEqualTo(ApplicationDocumentUploadState.READY);
            var replay = uploadService.upload(
                    owner,
                    "job-123",
                    application,
                    com.jobseekercopilot.documentstore.entity.DocumentType.CV,
                    com.jobseekercopilot.documentstore.entity.FileType.DOCX,
                    new MockMultipartFile("file", "cv.docx", DOCX_MIME, bytes),
                    "concurrent-key");
            assertThat(replay.state()).isEqualTo(ApplicationDocumentUploadState.READY);
            assertThat(replay.operationId()).isEqualTo(completed.operationId());
            assertThat(scans).hasValue(1);
            assertThat(documentRepository.findByUserId(owner)).hasSize(1);
            assertThat(activityRepository.findAll())
                    .filteredOn(event -> event.getDocumentId().equals(replay.documentId()))
                    .filteredOn(event -> "DOCUMENT_UPLOADED".equals(event.getEventType().name()))
                    .hasSize(1);
        } finally {
            allowScan.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cleanPdfCanPublishNoTextAndDownloadExactOriginalWithSafeHeaders()
            throws Exception {
        cleanScanner();
        String owner = "upload-owner-pdf";
        String application = UUID.randomUUID().toString();
        byte[] bytes = TestDocumentFiles.imageOnlyPdf();
        JsonNode response = upload(
                owner,
                application,
                "COVER_LETTER",
                "PDF",
                "letter.pdf",
                "application/pdf",
                bytes,
                "upload-pdf-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.extractionState").value("NO_TEXT"))
                .andReturnJson();

        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/artifacts/{artifactId}/download",
                        response.get("documentId").asText(),
                        response.get("artifactId").asText())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, owner))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0"))
                .andExpect(result -> assertThat(
                        result.getResponse().getContentAsByteArray()).isEqualTo(bytes));
    }

    @Test
    void infectedAndUnavailableScansNeverCreateSelectableVersions()
            throws Exception {
        when(malwareScanner.scan(any())).thenReturn(new MalwareScanResult(
                MalwareScanVerdict.INFECTED,
                "CLAMAV",
                "test",
                LocalDateTime.now()));
        upload(
                "upload-owner-infected",
                UUID.randomUUID().toString(),
                "CV",
                "DOCX",
                "cv.docx",
                DOCX_MIME,
                TestDocumentFiles.validDocx(),
                "infected-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("MALWARE_DETECTED"))
                .andExpect(jsonPath("$.documentId").doesNotExist());

        when(malwareScanner.scan(any())).thenThrow(
                new MalwareScannerUnavailableException("private scanner detail"));
        upload(
                "upload-owner-unavailable",
                UUID.randomUUID().toString(),
                "CV",
                "DOCX",
                "cv.docx",
                DOCX_MIME,
                TestDocumentFiles.validDocx(),
                "unavailable-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SCAN_UNAVAILABLE"))
                .andExpect(jsonPath("$.failureCode").value("SCANNER_UNAVAILABLE"))
                .andExpect(jsonPath("$.failureMessage")
                        .value("Document security scanning is temporarily unavailable."));

        assertThat(documentRepository.findByUserId("upload-owner-infected")).isEmpty();
        assertThat(documentRepository.findByUserId("upload-owner-unavailable")).isEmpty();
    }

    @Test
    void structuralRejectionHappensBeforeScannerAndChangedReplayConflicts()
            throws Exception {
        upload(
                "upload-owner-invalid",
                UUID.randomUUID().toString(),
                "CV",
                "PDF",
                "cv.pdf",
                "application/pdf",
                "not-a-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "invalid-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("VALIDATION_REJECTED"));
        verify(malwareScanner, never()).scan(any());

        cleanScanner();
        String application = UUID.randomUUID().toString();
        upload(
                "upload-owner-conflict",
                application,
                "CV",
                "DOCX",
                "cv.docx",
                DOCX_MIME,
                TestDocumentFiles.validDocx(),
                "same-key")
                .andExpect(status().isOk());
        upload(
                "upload-owner-conflict",
                application,
                "CV",
                "PDF",
                "cv.pdf",
                "application/pdf",
                TestDocumentFiles.validPdf(),
                "same-key")
                .andExpect(status().isConflict());
        assertThat(documentRepository.findByUserId("upload-owner-conflict"))
                .hasSize(1);
    }

    @Test
    void statusIsOwnerScopedAndOriginalByteQuotaFailsClosed()
            throws Exception {
        cleanScanner();
        int previous = (int) uploadProperties.getMaximumOriginalBytesPerOwner();
        uploadProperties.setMaximumOriginalBytesPerOwner(1);
        try {
            JsonNode response = upload(
                    "upload-owner-quota",
                    UUID.randomUUID().toString(),
                    "CV",
                    "DOCX",
                    "cv.docx",
                    DOCX_MIME,
                    TestDocumentFiles.validDocx(),
                    "quota-key")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("REJECTED"))
                    .andExpect(jsonPath("$.failureCode").value("UPLOAD_CONFLICT"))
                    .andReturnJson();
            mockMvc.perform(get(
                            "/api/v1/application-document-uploads/{id}",
                            response.get("operationId").asText())
                            .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                            .header(DocumentOwnerResolver.OWNER_HEADER, "different-owner"))
                    .andExpect(status().isNotFound());
            assertThat(documentRepository.findByUserId("upload-owner-quota"))
                    .isEmpty();
        } finally {
            uploadProperties.setMaximumOriginalBytesPerOwner(previous);
        }
    }

    @Test
    void familyAndVersionQuotasLeaveExistingReadyVersionUntouched()
            throws Exception {
        cleanScanner();
        int previousFamilies = uploadProperties.getMaximumFamiliesPerOwner();
        int previousVersions = uploadProperties.getMaximumVersionsPerFamily();
        try {
            uploadProperties.setMaximumFamiliesPerOwner(1);
            uploadProperties.setMaximumVersionsPerFamily(1);
            String owner = "upload-owner-family-quota";
            String application = UUID.randomUUID().toString();
            upload(
                    owner,
                    application,
                    "CV",
                    "DOCX",
                    "cv.docx",
                    DOCX_MIME,
                    TestDocumentFiles.validDocx(),
                    "family-first")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("READY"));
            upload(
                    owner,
                    application,
                    "CV",
                    "PDF",
                    "cv.pdf",
                    "application/pdf",
                    TestDocumentFiles.validPdf(),
                    "family-second-version")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("REJECTED"))
                    .andExpect(jsonPath("$.failureCode").value("UPLOAD_CONFLICT"));
            upload(
                    owner,
                    UUID.randomUUID().toString(),
                    "COVER_LETTER",
                    "DOCX",
                    "letter.docx",
                    DOCX_MIME,
                    TestDocumentFiles.validDocx(),
                    "family-second-family")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("REJECTED"));

            assertThat(documentRepository.findByUserId(owner)).hasSize(1);
            assertThat(documentRepository.findByUserId(owner).get(0)
                    .getLifecycleState()).isEqualTo(DocumentLifecycleState.APPROVED);
        } finally {
            uploadProperties.setMaximumFamiliesPerOwner(previousFamilies);
            uploadProperties.setMaximumVersionsPerFamily(previousVersions);
        }
    }

    @Test
    void uploadObservabilityDoesNotLogFilenameOrContentFingerprint(
            CapturedOutput output) throws Exception {
        cleanScanner();
        byte[] bytes = TestDocumentFiles.validDocx();
        String filename = "private-candidate-document.docx";
        upload(
                "upload-owner-observability",
                UUID.randomUUID().toString(),
                "CV",
                "DOCX",
                filename,
                DOCX_MIME,
                bytes,
                "observability-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));

        assertThat(output.getAll())
                .doesNotContain(filename)
                .doesNotContain(ObjectIntegrity.sha256(bytes))
                .doesNotContain("Synthetic CV");
    }

    private void cleanScanner() {
        when(malwareScanner.scan(any())).thenReturn(new MalwareScanResult(
                MalwareScanVerdict.CLEAN,
                "CLAMAV",
                "test",
                LocalDateTime.now()));
    }

    private UploadResult upload(
            String owner,
            String application,
            String documentType,
            String fileType,
            String fileName,
            String contentType,
            byte[] content,
            String idempotencyKey) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, contentType, content);
        return new UploadResult(mockMvc.perform(multipart(
                                "/api/v1/applications/{applicationId}/documents/{documentType}/uploads",
                                application,
                                documentType)
                        .file(file)
                        .param("jobId", "job-123")
                        .param("fileType", fileType)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, owner)
                        .header("Idempotency-Key", idempotencyKey)));
    }

    private final class UploadResult {
        private final org.springframework.test.web.servlet.ResultActions actions;

        private UploadResult(
                org.springframework.test.web.servlet.ResultActions actions) {
            this.actions = actions;
        }

        private UploadResult andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher)
                throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnJson() throws Exception {
            return objectMapper.readTree(actions.andReturn()
                    .getResponse()
                    .getContentAsString());
        }
    }
}

package com.jobseekercopilot.documentstore.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentEvidenceProvenance;
import com.jobseekercopilot.documentstore.dto.EvidenceRevisionReference;
import com.jobseekercopilot.documentstore.dto.EvidenceSection;
import com.jobseekercopilot.documentstore.dto.GenerationMetadata;
import com.jobseekercopilot.documentstore.dto.ValidatedClaimLedger;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.ValidatedClaimLedgerDigest;
import com.jobseekercopilot.documentstore.service.ApplicationAssociationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class GeneratedDocumentControllerIntegrationTest {

    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneratedDocumentRepository repository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @MockBean
    private ApplicationAssociationClient applicationAssociationClient;

    @Test
    void createDocument_ShouldReturn201() throws Exception {
        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Generated CV content...")
                .build();

        mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.jobId").value("job-456"))
                .andExpect(jsonPath("$.documentType").value("CV"))
                .andExpect(jsonPath("$.title").value("Java Developer CV"))
                .andExpect(jsonPath("$.content").value("Generated CV content..."))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void preservesExactEvidenceProvenanceAndParentVersion() throws Exception {
        UUID familyId =
                UUID.fromString("10000000-0000-4000-8000-000000000001");
        CreateDocumentRequest firstRequest = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("Evidence-grounded CV")
                .content("Validated CV content")
                .generationMetadata(generationMetadata())
                .evidenceProvenance(evidenceProvenance())
                .build();

        String first = mockMvc.perform(post("/api/v1/documents")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groundingState")
                        .value("AI_GENERATED_EVIDENCE_VALIDATED"))
                .andExpect(jsonPath("$.evidenceProvenance.evidenceSnapshotId")
                        .value("30000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.evidenceProvenance.evidenceRevisions[0].revisionNumber")
                        .value(3))
                .andExpect(jsonPath("$.parentDocumentId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID firstId = UUID.fromString(
                objectMapper.readTree(first).path("id").asText());

        mockMvc.perform(patch("/api/v1/documents/{id}/approve", firstId)
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "user-123"))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/documents/{id}/reference", firstId)
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceProvenance.claimLedger.ledgerSha256")
                        .value(evidenceProvenance()
                                .claimLedger()
                                .ledgerSha256()));

        CreateDocumentRequest secondRequest = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("Evidence-grounded CV revision")
                .content("New validated CV content")
                .generationMetadata(generationMetadata())
                .evidenceProvenance(evidenceProvenance())
                .build();
        mockMvc.perform(post("/api/v1/documents")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parentDocumentId")
                        .value(firstId.toString()))
                .andExpect(jsonPath("$.parentDocumentVersion").value(1));
    }

    @Test
    void rejectsAClaimLedgerWhoseExactClaimsDoNotMatchItsDigest()
            throws Exception {
        DocumentEvidenceProvenance valid = evidenceProvenance();
        ValidatedClaimLedger invalid = new ValidatedClaimLedger(
                valid.claimLedger().ledgerId(),
                "f".repeat(64),
                valid.claimLedger().policyVersion(),
                valid.claimLedger().parserVersion(),
                valid.claimLedger().claims());
        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Invalid provenance")
                .content("Content")
                .generationMetadata(generationMetadata())
                .evidenceProvenance(new DocumentEvidenceProvenance(
                        valid.profileRevisionId(),
                        valid.profileContentDigest(),
                        valid.evidenceSnapshotId(),
                        valid.evidenceSnapshotDigest(),
                        valid.evidenceRevisions(),
                        valid.sectionOrder(),
                        invalid,
                        valid.generatedAt()))
                .build();

        mockMvc.perform(post("/api/v1/documents")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDocument_WithMissingFields_ShouldReturn400() throws Exception {
        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .build();

        mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDocument_IdempotencyKeyReplaysAndRejectsDifferentRequest() throws Exception {
        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .applicationId("application-123")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Generated CV content...")
                .build();

        String first = mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "generate-application-123-cv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String replay = mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "generate-application-123-cv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(
                objectMapper.readTree(first).path("id").asText(),
                objectMapper.readTree(replay).path("id").asText());
        request.setContent("Different content");
        mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "generate-application-123-cv")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key was already used for a different document operation."));
        org.junit.jupiter.api.Assertions.assertEquals(
                1, repository.findByUserId("user-123").size());
    }

    @Test
    void getDocumentById_WhenExists_ShouldReturn200() throws Exception {
        GeneratedDocument saved = repository.save(GeneratedDocument.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Generated CV content...")
                .build());

        mockMvc.perform(get("/api/v1/documents/{id}", saved.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.title").value("Java Developer CV"));
    }

    @Test
    void getDocumentById_WhenNotExists_ShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}", UUID.randomUUID())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDocumentsByUserId_ShouldReturnList() throws Exception {
        repository.save(GeneratedDocument.builder()
                .userId("user-test")
                .jobId("job-1")
                .documentType(DocumentType.CV)
                .title("Developer CV")
                .content("Content 1")
                .build());

        repository.save(GeneratedDocument.builder()
                .userId("user-test")
                .jobId("job-2")
                .documentType(DocumentType.COVER_LETTER)
                .title("Developer Cover Letter")
                .content("Content 2")
                .build());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "user-test")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getDocumentsByUserIdAndJobId_ShouldReturnList() throws Exception {
        repository.save(GeneratedDocument.builder()
                .userId("user-test")
                .jobId("job-1")
                .documentType(DocumentType.CV)
                .title("Developer CV")
                .content("Content 1")
                .build());

        repository.save(GeneratedDocument.builder()
                .userId("user-test")
                .jobId("job-1")
                .documentType(DocumentType.COVER_LETTER)
                .title("Developer Cover Letter")
                .content("Content 2")
                .build());

        mockMvc.perform(get("/api/v1/documents/user/{userId}/job/{jobId}", "user-test", "job-1")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void familyHistoryIsContentFreeNewestFirstAndUsesExactArtifactManifest()
            throws Exception {
        UUID familyId = UUID.randomUUID();
        GeneratedDocument first = repository.saveAndFlush(GeneratedDocument.builder()
                .userId("history-owner")
                .jobId("job-history")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("First CV")
                .content("secret first content")
                .contentSha256("a".repeat(64))
                .version(1)
                .lifecycleState(DocumentLifecycleState.APPROVED)
                .approvedAt(java.time.LocalDateTime.now())
                .approvedBy("history-owner")
                .originalFilename("private-original-name.docx")
                .sourceType(DocumentSourceType.UPLOADED)
                .build());
        GeneratedDocument second = repository.saveAndFlush(GeneratedDocument.builder()
                .userId("history-owner")
                .jobId("job-history")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("Second CV")
                .content("secret second content")
                .contentSha256("b".repeat(64))
                .version(2)
                .sourceType(DocumentSourceType.UPLOADED)
                .build());
        ExportedDocumentFile artifact = fileRepository.saveAndFlush(
                ExportedDocumentFile.builder()
                        .generatedDocumentId(first.getId())
                        .ownerId("history-owner")
                        .fileType(FileType.DOCX)
                        .fileName("private-original-name.docx")
                        .mimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                        .source(FileSource.USER_UPLOADED)
                        .active(true)
                        .version(1)
                        .storageKey("history/" + UUID.randomUUID())
                        .contentSize(1234)
                        .contentSha256("c".repeat(64))
                        .storageStatus(ObjectStorageStatus.AVAILABLE)
                        .build());

        mockMvc.perform(get("/api/v1/documents/families/{familyId}", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "history-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentFamilyId").value(familyId.toString()))
                .andExpect(jsonPath("$.versions[0].documentId").value(second.getId().toString()))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[1].version").value(1))
                .andExpect(jsonPath("$.versions[1].artifacts[0].artifactId")
                        .value(artifact.getId().toString()))
                .andExpect(jsonPath("$.versions[1].artifacts[0].role").value("ORIGINAL"))
                .andExpect(jsonPath("$.versions[1].artifacts[0].format").value("DOCX"))
                .andExpect(jsonPath("$.versions[1].artifacts[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.versions[1].artifacts[0].size").value(1234))
                .andExpect(jsonPath("$.versions[1].content").doesNotExist())
                .andExpect(jsonPath("$.versions[1].contentSha256").doesNotExist())
                .andExpect(jsonPath("$.versions[1].originalFilename").doesNotExist())
                .andExpect(jsonPath("$.versions[1].artifacts[0].fileName").doesNotExist())
                .andExpect(jsonPath("$.versions[1].artifacts[0].contentSha256").doesNotExist());

        mockMvc.perform(get("/api/v1/documents/families/{familyId}", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "another-owner"))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvalDoesNotMoveCurrentAndFamilyCommandIsConcurrencyProtectedAndReplaySafe()
            throws Exception {
        UUID familyId = UUID.randomUUID();
        GeneratedDocument first = repository.saveAndFlush(GeneratedDocument.builder()
                .userId("current-owner")
                .jobId("job-current")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("First CV")
                .content("first")
                .version(1)
                .sourceType(DocumentSourceType.UPLOADED)
                .build());
        GeneratedDocument second = repository.saveAndFlush(GeneratedDocument.builder()
                .userId("current-owner")
                .jobId("job-current")
                .documentFamilyId(familyId)
                .documentType(DocumentType.CV)
                .title("Second CV")
                .content("second")
                .version(2)
                .sourceType(DocumentSourceType.UPLOADED)
                .build());

        for (UUID documentId : List.of(first.getId(), second.getId())) {
            mockMvc.perform(patch("/api/v1/documents/{id}/approve", documentId)
                            .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                            .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current").value(false));
        }

        String selectFirst = """
                {"documentId":"%s","expectedCurrentState":"NONE"}
                """.formatted(first.getId());
        String originalResponse = mockMvc.perform(patch(
                        "/api/v1/documents/families/{familyId}/current", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner")
                        .header("Idempotency-Key", "select-first-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectFirst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDocumentId").value(first.getId().toString()))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(patch(
                        "/api/v1/documents/families/{familyId}/current", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner")
                        .header("Idempotency-Key", "select-first-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectFirst))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(
                objectMapper.readTree(originalResponse).path("commandId").asText(),
                objectMapper.readTree(replayResponse).path("commandId").asText());

        String stale = """
                {"documentId":"%s","expectedCurrentState":"NONE"}
                """.formatted(second.getId());
        mockMvc.perform(patch("/api/v1/documents/families/{familyId}/current", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner")
                        .header("Idempotency-Key", "stale-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stale))
                .andExpect(status().isConflict());

        String selectSecond = """
                {"documentId":"%s","expectedCurrentState":"SELECTED","expectedCurrentDocumentId":"%s"}
                """.formatted(second.getId(), first.getId());
        mockMvc.perform(patch("/api/v1/documents/families/{familyId}/current", familyId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner")
                        .header("Idempotency-Key", "select-second-current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectSecond))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDocumentId").value(second.getId().toString()))
                .andExpect(jsonPath("$.currentVersion").value(2));

        mockMvc.perform(get("/api/v1/documents/families?page=0&size=1")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "current-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].currentDocumentId")
                        .value(second.getId().toString()))
                .andExpect(jsonPath("$.items[0].versionCount").value(2));
    }

    @Test
    void deleteDocument_ShouldReturn204() throws Exception {
        GeneratedDocument saved = repository.save(GeneratedDocument.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Generated CV content...")
                .build());

        mockMvc.perform(delete("/api/v1/documents/{id}", saved.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isNoContent());
    }

    private GenerationMetadata generationMetadata() {
        return GenerationMetadata.builder()
                .releaseId("cv-cover-letter-1.4.0")
                .bundleId("cv-cover-letter")
                .bundleVersion("1.4.0")
                .bundleSha256("a".repeat(64))
                .templateVersion("1.3.0")
                .templateSha256("b".repeat(64))
                .rulesVersion("1.4.0")
                .rulesSha256("c".repeat(64))
                .schemaId("cv-cover-letter-output")
                .schemaVersion("3.0.0")
                .schemaSha256("d".repeat(64))
                .evaluationPolicyVersion("1.2.0")
                .evaluationPolicySha256("e".repeat(64))
                .build();
    }

    private DocumentEvidenceProvenance evidenceProvenance() {
        List<ValidatedClaimLedger.ValidatedClaim> claims = List.of(
                new ValidatedClaimLedger.ValidatedClaim(
                        "CLAIM-001",
                        ValidatedClaimLedger.ClaimDisposition.SUPPORTED,
                        List.of("50000000-0000-4000-8000-000000000001"),
                        List.of("/cv/profile/summary"),
                        "Supported"));
        ValidatedClaimLedger unsigned = new ValidatedClaimLedger(
                UUID.fromString(
                        "60000000-0000-4000-8000-000000000001"),
                "0".repeat(64),
                "2.0.0",
                "3.0.0",
                claims);
        ValidatedClaimLedger ledger = new ValidatedClaimLedger(
                unsigned.ledgerId(),
                ValidatedClaimLedgerDigest.calculate(unsigned),
                unsigned.policyVersion(),
                unsigned.parserVersion(),
                claims);
        return new DocumentEvidenceProvenance(
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000001"),
                "1".repeat(64),
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000001"),
                "2".repeat(64),
                List.of(new EvidenceRevisionReference(
                        UUID.fromString(
                                "40000000-0000-4000-8000-000000000001"),
                        UUID.fromString(
                                "50000000-0000-4000-8000-000000000002"),
                        3,
                        EvidenceSection.PROJECT,
                        "3".repeat(64))),
                List.of(EvidenceSection.PROJECT),
                ledger,
                OffsetDateTime.parse("2026-07-29T03:00:00Z"));
    }
}

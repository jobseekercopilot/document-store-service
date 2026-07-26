package com.jobseekercopilot.documentstore.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.GenerationMetadata;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentLifecycleIntegrationTest {

    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";
    private static final String OWNER = "lifecycle-owner";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneratedDocumentRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void draftRequiresExplicitApprovalAndGeneratedProvenance() throws Exception {
        JsonNode draft = create(documentRequest("Draft without provenance", null));
        UUID id = UUID.fromString(draft.path("id").asText());

        assertEquals("DRAFT", draft.path("lifecycleState").asText());
        assertEquals(false, draft.path("current").asBoolean());

        mockMvc.perform(get("/api/v1/documents/{id}/reference", id)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Document version is not approved for application use."));

        mockMvc.perform(patch("/api/v1/documents/{id}/approve", id)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Generated document provenance is required before approval."));

        CreateDocumentRequest invalidCurrent = documentRequest(
                "Draft cannot start current", generationMetadata("a"));
        invalidCurrent.setActive(true);
        mockMvc.perform(post("/api/v1/documents")
                        .headers(serviceHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCurrent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Documents are created as drafts and must be explicitly approved."));
    }

    @Test
    void approvedReferenceRemainsPinnedWhenARegeneratedVersionBecomesCurrent()
            throws Exception {
        JsonNode firstDraft = create(documentRequest(
                "Approved version one", generationMetadata("a")));
        UUID firstId = UUID.fromString(firstDraft.path("id").asText());
        UUID familyId = UUID.fromString(firstDraft.path("documentFamilyId").asText());
        String firstHash = firstDraft.path("contentSha256").asText();

        approve(firstId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("APPROVED"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.approvedBy").value(OWNER));

        mockMvc.perform(get("/api/v1/documents/{id}/reference", firstId)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentSha256").value(firstHash))
                .andExpect(jsonPath("$.content").doesNotExist());

        mockMvc.perform(get("/api/v1/documents/{id}/reference", firstId)
                        .headers(serviceHeaders("different-owner")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{id}/reference", UUID.randomUUID())
                        .headers(serviceHeaders("different-owner")))
                .andExpect(status().isNotFound());

        CreateDocumentRequest wrongKind =
                documentRequest("Wrong family type", generationMetadata("b"));
        wrongKind.setDocumentFamilyId(familyId);
        wrongKind.setDocumentType(DocumentType.COVER_LETTER);
        mockMvc.perform(post("/api/v1/documents")
                        .headers(serviceHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongKind)))
                .andExpect(status().isNotFound());

        CreateDocumentRequest replacement =
                documentRequest("Approved version two", generationMetadata("b"));
        replacement.setDocumentFamilyId(familyId);
        JsonNode secondDraft = create(replacement);
        UUID secondId = UUID.fromString(secondDraft.path("id").asText());
        String secondHash = secondDraft.path("contentSha256").asText();
        assertEquals(2, secondDraft.path("version").asInt());
        assertNotEquals(firstHash, secondHash);

        approve(secondId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));

        mockMvc.perform(get("/api/v1/documents/{id}/reference", firstId)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentSha256").value(firstHash))
                .andExpect(jsonPath("$.current").value(false));
        mockMvc.perform(get("/api/v1/documents/{id}/reference", secondId)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentSha256").value(secondHash))
                .andExpect(jsonPath("$.current").value(true));

        mockMvc.perform(delete("/api/v1/documents/{id}", firstId)
                        .headers(serviceHeaders(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Approved document versions require retention-aware deletion."));
    }

    @Test
    void uploadedDraftCanBeApprovedWithoutAiProvenance() throws Exception {
        CreateDocumentRequest upload =
                documentRequest("Uploaded CV", null);
        upload.setSourceType(DocumentSourceType.UPLOADED);
        JsonNode draft = create(upload);

        approve(UUID.fromString(draft.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("APPROVED"));
    }

    private JsonNode create(CreateDocumentRequest request) throws Exception {
        String response = mockMvc.perform(post("/api/v1/documents")
                        .headers(serviceHeaders(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private org.springframework.test.web.servlet.ResultActions approve(UUID id)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/documents/{id}/approve", id)
                .headers(serviceHeaders(OWNER)));
    }

    private CreateDocumentRequest documentRequest(
            String content, GenerationMetadata generationMetadata) {
        return CreateDocumentRequest.builder()
                .userId(OWNER)
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Private beta CV")
                .content(content)
                .generationMetadata(generationMetadata)
                .build();
    }

    private GenerationMetadata generationMetadata(String hashCharacter) {
        String hash = hashCharacter.repeat(64);
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

    private org.springframework.http.HttpHeaders serviceHeaders(String owner) {
        org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();
        headers.add(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN);
        headers.add(DocumentOwnerResolver.OWNER_HEADER, owner);
        return headers;
    }
}

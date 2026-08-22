package com.jobseekercopilot.documentstore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.service.ApplicationAssociationClient;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class DocumentActivityIntegrationTest {
    private static final String OWNER = "activity-owner";
    private static final String OTHER_OWNER = "other-owner";
    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";
    private static final String READER_TOKEN =
            "test-only-document-reader-token-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentActivityEventRepository activityRepository;

    @MockBean
    private ApplicationAssociationClient applicationAssociationClient;

    @Test
    void recordsSafeOwnerScopedActivityExactlyOnceForStateChanges()
            throws Exception {
        CreateDocumentRequest request = CreateDocumentRequest.builder()
                .userId(OWNER)
                .jobId("activity-job")
                .documentType(DocumentType.CV)
                .sourceType(DocumentSourceType.UPLOADED)
                .title("Sensitive title must not be projected")
                .content("Sensitive CV content must not be projected")
                .build();
        String requestJson = objectMapper.writeValueAsString(request);
        String createdJson = mockMvc.perform(post("/api/v1/documents")
                        .headers(producerHeaders(OWNER))
                        .header("Idempotency-Key", "activity-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createdJson);
        UUID documentId = UUID.fromString(created.path("id").asText());
        UUID familyId = UUID.fromString(
                created.path("documentFamilyId").asText());

        mockMvc.perform(post("/api/v1/documents")
                        .headers(producerHeaders(OWNER))
                        .header("Idempotency-Key", "activity-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/v1/documents/{id}/approve", documentId)
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk());

        Map<String, Object> currentRequest = Map.of(
                "documentId", documentId,
                "expectedCurrentState", "NONE");
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(patch(
                            "/api/v1/documents/families/{familyId}/current",
                            familyId)
                            .headers(producerHeaders(OWNER))
                            .header("Idempotency-Key", "activity-current")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(currentRequest)))
                    .andExpect(status().isOk());
        }

        byte[] pdf = TestDocumentFiles.validPdf();
        CreateDocumentFileRequest fileRequest =
                CreateDocumentFileRequest.builder()
                        .generatedDocumentId(documentId)
                        .fileType(FileType.PDF)
                        .fileName("sensitive-file-name.pdf")
                        .mimeType(MediaType.APPLICATION_PDF_VALUE)
                        .fileContentBase64(Base64.getEncoder().encodeToString(pdf))
                        .build();
        String fileJson = mockMvc.perform(post("/api/v1/document-files")
                        .headers(producerHeaders(OWNER))
                        .header("Idempotency-Key", "activity-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID artifactId = UUID.fromString(
                objectMapper.readTree(fileJson).path("id").asText());
        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/artifacts/{artifactId}/download",
                        documentId,
                        artifactId)
                        .headers(producerHeaders(OWNER)))
                .andExpect(status().isOk());

        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(patch("/api/v1/documents/{id}/archive", documentId)
                            .headers(producerHeaders(OWNER)))
                    .andExpect(status().isOk());
        }
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(patch("/api/v1/documents/{id}/restore", documentId)
                            .headers(producerHeaders(OWNER)))
                    .andExpect(status().isOk());
        }

        String activityJson = mockMvc.perform(get("/api/v1/document-activity")
                        .headers(readerHeaders(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[*].eventType", containsInAnyOrder(
                        "DOCUMENT_VERSION_CREATED",
                        "DOCUMENT_CURRENT_VERSION_CHANGED",
                        "DOCUMENT_VERSION_DOWNLOADED",
                        "DOCUMENT_VERSION_ARCHIVED",
                        "DOCUMENT_VERSION_RESTORED")))
                .andExpect(jsonPath("$.items[*].documentType",
                        containsInAnyOrder("CV", "CV", "CV", "CV", "CV")))
                .andExpect(jsonPath("$.items[*].version",
                        containsInAnyOrder(1, 1, 1, 1, 1)))
                .andReturn().getResponse().getContentAsString();

        String normalized = activityJson.toLowerCase();
        assertThat(normalized)
                .doesNotContain("sensitive")
                .doesNotContain("content")
                .doesNotContain("filename")
                .doesNotContain("sha256")
                .doesNotContain("scanner")
                .doesNotContain("notes");
        assertThat(activityRepository.count()).isEqualTo(5);

        mockMvc.perform(get("/api/v1/document-activity")
                        .headers(readerHeaders(OTHER_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    private HttpHeaders producerHeaders(String owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN);
        headers.set(DocumentOwnerResolver.OWNER_HEADER, owner);
        return headers;
    }

    private HttpHeaders readerHeaders(String owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN);
        headers.set(DocumentOwnerResolver.OWNER_HEADER, owner);
        return headers;
    }
}

package com.jobseekercopilot.documentstore.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}

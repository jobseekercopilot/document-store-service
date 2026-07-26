package com.jobseekercopilot.documentstore;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "environment-data.enabled=true")
@AutoConfigureMockMvc
class DocumentSecurityIntegrationTest {

    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";
    private static final String READER_TOKEN =
            "test-only-document-reader-token-32-bytes";
    private static final String ENVIRONMENT_DATA_TOKEN =
            "test-only-environment-data-token-32-bytes";

    private static final TestJwksServer JWKS = new TestJwksServer();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("document-store.security.jwk-set-uri", JWKS::jwkSetUri);
        registry.add("document-store.security.issuer", () -> TestJwksServer.ISSUER);
        registry.add("document-store.security.audience", () -> TestJwksServer.AUDIENCE);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentObjectStorage objectStorage;

    @BeforeEach
    void cleanDatabase() {
        fileRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @Test
    void validUserTokenStrictlyBindsCreateAndListsToItsSubject() throws Exception {
        mockMvc.perform(post("/api/v1/documents")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice"))
                        .header("X-User-Id", "victim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("alice"));

        mockMvc.perform(post("/api/v1/documents")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("victim"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Document not found."));

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice"))
                        .header(DocumentOwnerResolver.OWNER_HEADER, "victim"))
                .andExpect(status().isNotFound());

        assertTrue(documentRepository.findByUserId("victim").isEmpty());
    }

    @Test
    void foreignAndMissingDocumentAndFileIdsHaveTheSameStableDenial() throws Exception {
        GeneratedDocument aliceDocument = saveDocument("alice", "application-a");
        ExportedDocumentFile aliceFile = saveFile(aliceDocument);
        UUID missingDocument = UUID.randomUUID();
        UUID missingFile = UUID.randomUUID();

        assertSameDenial(
                mockMvc.perform(get("/api/v1/documents/{id}", aliceDocument.getId())
                                .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                        .andExpect(status().isNotFound())
                        .andReturn(),
                mockMvc.perform(get("/api/v1/documents/{id}", missingDocument)
                                .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                        .andExpect(status().isNotFound())
                        .andReturn(),
                "Document not found.");

        assertSameDenial(
                mockMvc.perform(get("/api/v1/document-files/{id}/download", aliceFile.getId())
                                .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                        .andExpect(status().isNotFound())
                        .andReturn(),
                mockMvc.perform(get("/api/v1/document-files/{id}/download", missingFile)
                                .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                        .andExpect(status().isNotFound())
                        .andReturn(),
                "Document file not found.");

        mockMvc.perform(patch("/api/v1/document-files/{id}/active", aliceFile.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/documents/{id}/files", aliceDocument.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/documents/{id}/files/latest", aliceDocument.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/documents/{id}", aliceDocument.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch(
                                "/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}",
                                "application-a",
                                "CV",
                                aliceDocument.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                                "/api/v1/documents/applications/{applicationId}/deactivate",
                                "application-a")
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest(aliceDocument.getId()))))
                .andExpect(status().isNotFound());

        MockMultipartFile replacement = new MockMultipartFile(
                "file",
                "replacement.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                TestDocumentFiles.validPdf());
        mockMvc.perform(multipart(
                                "/api/v1/documents/{generatedDocumentId}/files/upload",
                                aliceDocument.getId())
                        .file(replacement)
                        .param("fileType", "PDF")
                        .param("source", "USER_UPLOADED")
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        assertTrue(documentRepository.findByIdAndUserId(aliceDocument.getId(), "alice").isPresent());
        assertTrue(fileRepository.findById(aliceFile.getId()).orElseThrow().isActive());
        assertTrue(documentRepository.findById(aliceDocument.getId()).orElseThrow().isActive());
    }

    @Test
    void invalidAccessTokensFailUniformlyWithoutTokenOrProviderDetails() throws Exception {
        List<String> invalidTokens = List.of(
                "not-a-jwt",
                JWKS.expiredToken("expired-subject"),
                JWKS.forgedKnownKeyToken("forged-subject"),
                JWKS.unknownKeyToken("unknown-key-subject"),
                JWKS.wrongAlgorithmToken("wrong-algorithm-subject"),
                JWKS.wrongIssuerToken("wrong-issuer-subject"),
                JWKS.wrongAudienceToken("wrong-audience-subject"),
                JWKS.refreshTokenType("wrong-type-subject"),
                JWKS.missingSubjectToken());

        assertAuthenticationFailure(null);
        for (String token : invalidTokens) {
            assertAuthenticationFailure(token);
        }
    }

    @Test
    void serviceIdentitiesAreOwnerScopedDistinctAndLeastPrivilege() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andReturn();
        UUID documentId = UUID.fromString(objectMapper.readTree(
                        created.getResponse().getContentAsString())
                .path("id")
                .asText());

        mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("alice"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Document owner is required for service requests."));

        mockMvc.perform(post("/api/v1/documents")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(documentRequest("victim"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN,
                                READER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "alice"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(
                                DocumentOwnerResolver.OWNER_HEADER,
                                "alice",
                                "victim"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void environmentDataIdentityIsIndependentAndHealthIsPublic() throws Exception {
        saveDocument("alice", "application-a");

        mockMvc.perform(get("/internal/system-data/verify/documents/{userId}", "alice")
                        .header(
                                DocumentServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(1));

        mockMvc.perform(get("/internal/system-data/verify/documents/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/internal/system-data/verify/documents/{userId}", "alice")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/documents/user/{userId}", "alice")
                        .header(
                                DocumentServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void environmentDataSeedAndResetUseTheObjectStorageLifecycle() throws Exception {
        GeneratedDocument document = saveDocument("alice", "application-a");
        UUID fileId = UUID.randomUUID();
        byte[] content = TestDocumentFiles.validPdf();
        Map<String, Object> request = Map.of(
                "scenarioId", "object-storage-scenario",
                "userId", "alice",
                "documents", List.of(),
                "files", List.of(Map.of(
                        "id", fileId,
                        "generatedDocumentId", document.getId(),
                        "fileType", "PDF",
                        "fileName", "seeded.pdf",
                        "mimeType", MediaType.APPLICATION_PDF_VALUE,
                        "source", "GENERATED",
                        "active", true,
                        "version", 1,
                        "fileContent", content)));

        mockMvc.perform(post("/internal/system-data/seed/documents")
                        .header(
                                DocumentServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.files").value(1));

        String key = fileRepository.findById(fileId).orElseThrow().getStorageKey();
        assertTrue(objectStorage.exists(key));
        mockMvc.perform(delete(
                        "/internal/system-data/scenario/{scenarioId}/documents/{userId}",
                        "object-storage-scenario",
                        "alice")
                        .header(
                                DocumentServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.files").value(1));
        assertFalse(objectStorage.exists(key));
    }

    private void assertAuthenticationFailure(String token) throws Exception {
        var request = get("/api/v1/documents/user/{userId}", "alice");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Valid authentication is required."))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        if (token != null) {
            assertFalse(response.contains(token));
        }
        assertFalse(response.contains("127.0.0.1"));
        assertFalse(response.contains("subject"));
        assertFalse(response.contains("Jwt"));
    }

    private void assertSameDenial(
            MvcResult first,
            MvcResult second,
            String expectedMessage) throws Exception {
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(firstBody.path("status"), secondBody.path("status"));
        assertEquals(firstBody.path("message"), secondBody.path("message"));
        assertEquals(expectedMessage, firstBody.path("message").asText());
    }

    private GeneratedDocument saveDocument(String owner, String applicationId) {
        return documentRepository.save(GeneratedDocument.builder()
                .userId(owner)
                .jobId("job-456")
                .applicationId(applicationId)
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Synthetic content")
                .lifecycleState(DocumentLifecycleState.APPROVED)
                .active(true)
                .approvedAt(java.time.LocalDateTime.now())
                .approvedBy(owner)
                .build());
    }

    private ExportedDocumentFile saveFile(GeneratedDocument document) {
        byte[] content = TestDocumentFiles.validPdf();
        UUID fileId = UUID.randomUUID();
        String key = ObjectKeyFactory.forFile(document.getId(), fileId, 1);
        String sha256 = ObjectIntegrity.sha256(content);
        objectStorage.put(key, content, MediaType.APPLICATION_PDF_VALUE, sha256);
        return fileRepository.save(ExportedDocumentFile.builder()
                .id(fileId)
                .generatedDocumentId(document.getId())
                .ownerId(document.getUserId())
                .fileType(FileType.PDF)
                .fileName("cv.pdf")
                .mimeType(MediaType.APPLICATION_PDF_VALUE)
                .source(FileSource.GENERATED)
                .active(true)
                .version(1)
                .storageKey(key)
                .contentSize(content.length)
                .contentSha256(sha256)
                .storageStatus(ObjectStorageStatus.AVAILABLE)
                .build());
    }

    private CreateDocumentRequest documentRequest(String owner) {
        return CreateDocumentRequest.builder()
                .userId(owner)
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Synthetic content")
                .build();
    }

    private CreateDocumentFileRequest fileRequest(UUID documentId) {
        return CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileName("cv.pdf")
                .mimeType(MediaType.APPLICATION_PDF_VALUE)
                .fileContentBase64(Base64.getEncoder().encodeToString(
                        TestDocumentFiles.validPdf()))
                .build();
    }

    private static String authorization(String subject) {
        return "Bearer " + JWKS.validToken(subject);
    }
}

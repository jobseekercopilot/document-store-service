package com.jobseekercopilot.documentstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiExportTest {

    private static final Path CONTRACT = Path.of("contracts/openapi.json");
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

    @Test
    void publishedContractMatchesTheRunningApplication() throws Exception {
        String specification = mockMvc.perform(get("/v3/api-docs")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + JWKS.validToken("contract-reviewer")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var generated = objectMapper.readTree(specification);

        assertEquals("4.0.0", generated.at("/info/version").asText());
        var upload = generated.at(
                "/paths/~1api~1v1~1applications~1{applicationId}~1documents~1{documentType}~1uploads/post");
        assertTrue(upload.isObject());
        assertTrue(upload.at("/requestBody/content/multipart~1form-data").isObject());
        assertTrue(upload.at("/security/0/serviceToken").isArray());
        assertTrue(generated.at(
                "/paths/~1api~1v1~1application-document-uploads~1{operationId}/get").isObject());
        var uploadState = generated.at(
                "/components/schemas/ApplicationDocumentUploadResponse/properties/state/enum");
        for (String state : new String[] {
                "RECEIVED",
                "QUARANTINED",
                "SCANNING",
                "SCANNED_CLEAN",
                "EXTRACTING",
                "READY",
                "REJECTED",
                "FAILED",
                "SCAN_UNAVAILABLE"
        }) {
            assertTrue(uploadState.toString().contains("\"" + state + "\""));
        }
        assertTrue(generated.at(
                "/paths/~1api~1v1~1document-activity/get").isObject());
        var activity = generated.at(
                "/components/schemas/DocumentActivityEventResponse/properties");
        assertTrue(activity.path("eventType").isObject());
        assertTrue(activity.path("documentFamilyId").isObject());
        assertTrue(activity.path("version").isObject());
        assertTrue(activity.path("occurredAt").isObject());
        assertTrue(activity.path("content").isMissingNode());
        assertTrue(activity.path("fileName").isMissingNode());
        assertTrue(activity.path("contentSha256").isMissingNode());
        assertTrue(activity.path("scannerDetails").isMissingNode());
        assertTrue(activity.path("notes").isMissingNode());
        assertTrue(generated.at(
                        "/components/schemas/GeneratedDocumentResponse/properties")
                .has("purgedAt"));
        assertTrue(generated.at(
                        "/components/schemas/GeneratedDocumentResponse/properties")
                .has("unavailableReason"));
        var generatedDocument = generated.at(
                "/components/schemas/GeneratedDocumentResponse/properties");
        for (String property : new String[] {
                "originalContentSha256",
                "originalContentSize",
                "originalArtifactId",
                "originalFileType",
                "extractionState",
                "sourceType"
        }) {
            assertTrue(generatedDocument.has(property));
        }
        var documentReference = generated.at(
                "/components/schemas/DocumentReferenceResponse/properties");
        for (String property : new String[] {
                "originalContentSha256",
                "originalContentSize",
                "originalArtifactId",
                "originalFileType",
                "extractionState",
                "sourceType"
        }) {
            assertTrue(documentReference.has(property));
        }
        assertTrue(generated.at(
                        "/components/schemas/DocumentVersionHistoryItem/properties")
                .has("applicationAssociations"));
        assertTrue(generated.at(
                        "/components/schemas/DocumentTombstoneAssociationResponse/properties")
                .has("associationState"));
        assertFalse(generated.at(
                        "/components/schemas/DocumentTombstoneAssociationResponse/properties")
                .has("contentSha256"));
        assertEquals(
                "downloadDocumentArtifact",
                generated.at("/paths/~1api~1v1~1documents~1{generatedDocumentId}~1artifacts~1{artifactId}~1download/get/operationId")
                        .asText());
        var downloadHeaders = generated.at(
                "/paths/~1api~1v1~1documents~1{generatedDocumentId}~1artifacts~1{artifactId}~1download/get/responses/200/headers");
        for (String header : new String[] {
                "Content-Disposition",
                "X-Content-Type-Options",
                "Cache-Control",
                "Pragma",
                "Content-Length"
        }) {
            assertFalse(downloadHeaders.path(header).isMissingNode());
        }
        assertEquals(
                "Proprietary and confidential",
                generated.at("/info/license/name").asText());
        assertEquals(
                "http://localhost:8089",
                generated.at("/servers/0/url").asText());

        String formattedSpecification = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(generated)
                + System.lineSeparator();

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), formattedSpecification);

        if (Boolean.getBoolean("documentStore.updateContract")) {
            Files.writeString(CONTRACT, formattedSpecification);
        } else {
            assertEquals(
                    objectMapper.readTree(Files.readString(CONTRACT)),
                    generated,
                    "Published OpenAPI contract is stale; use the documented update command and review the diff");
        }
    }
}

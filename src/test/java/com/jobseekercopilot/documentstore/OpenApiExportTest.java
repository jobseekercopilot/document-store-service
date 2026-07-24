package com.jobseekercopilot.documentstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals("1.1.0", generated.at("/info/version").asText());
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

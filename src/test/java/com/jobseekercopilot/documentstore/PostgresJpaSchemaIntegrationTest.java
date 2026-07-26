package com.jobseekercopilot.documentstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PostgresJpaSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15.18-alpine3.23")
                    .withDatabaseName("document_store_jpa")
                    .withUsername("document_store")
                    .withPassword("document_store");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add(
                "spring.datasource.hikari.data-source-properties.sslmode",
                () -> "disable");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add(
                "document-store.database.production-safety-check", () -> "false");
    }

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Test
    void flywaySchemaIsCompatibleWithJpaTextAndByteMappings() {
        GeneratedDocument document = documentRepository.saveAndFlush(
                GeneratedDocument.builder()
                        .userId("synthetic-jpa-owner")
                        .jobId("synthetic-jpa-job")
                        .documentType(DocumentType.CV)
                        .title("Synthetic JPA mapping")
                        .content("Synthetic PostgreSQL text")
                        .build());

        byte[] bytes = "synthetic-postgresql-bytes".getBytes(StandardCharsets.UTF_8);
        ExportedDocumentFile file = fileRepository.saveAndFlush(
                ExportedDocumentFile.builder()
                        .generatedDocumentId(document.getId())
                        .fileType(FileType.PDF)
                        .fileName("synthetic.pdf")
                        .mimeType("application/pdf")
                        .fileContent(bytes)
                        .build());

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getContent())
                .isEqualTo("Synthetic PostgreSQL text");
        assertThat(fileRepository.findById(file.getId()).orElseThrow().getFileContent())
                .isEqualTo(bytes);
    }
}

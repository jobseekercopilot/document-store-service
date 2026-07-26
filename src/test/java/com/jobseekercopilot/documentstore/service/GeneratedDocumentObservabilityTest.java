package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GeneratedDocumentObservabilityTest {

    @Mock
    private GeneratedDocumentRepository repository;

    @Mock
    private DocumentRetentionService retentionService;

    @Mock
    private DocumentOperationLock operationLock;

    private SimpleMeterRegistry registry;
    private GeneratedDocumentService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new GeneratedDocumentService(
                repository,
                retentionService,
                operationLock,
                new DocumentStoreMetrics(registry));
    }

    @Test
    void createMetricsAndLogsExcludeDocumentAndOwnerData(
            CapturedOutput output) {
        when(repository.saveAndFlush(any(GeneratedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        String owner = "private-owner@example.test";
        String jobId = "private-job-123";
        String title = "Private application title";
        String content = "Private generated document content";
        String fileName = "private-owner-cv.docx";

        service.createDocument(
                owner,
                CreateDocumentRequest.builder()
                        .userId(owner)
                        .jobId(jobId)
                        .documentType(DocumentType.CV)
                        .title(title)
                        .content(content)
                        .originalFilename(fileName)
                        .build(),
                null);

        assertThat(registry.get(DocumentStoreMetrics.OPERATION_COUNT)
                        .tags(
                                "resource", "document",
                                "operation", "create",
                                "outcome", "success",
                                "reason", "none",
                                "document_type", "cv",
                                "file_type", "unknown")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentStoreMetrics.PAYLOAD_SIZE)
                        .tags(
                                "resource", "document",
                                "direction", "stored",
                                "document_type", "cv",
                                "file_type", "unknown")
                        .summary()
                        .totalAmount())
                .isEqualTo(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(output).doesNotContain(
                owner, jobId, title, content, fileName);
    }
}

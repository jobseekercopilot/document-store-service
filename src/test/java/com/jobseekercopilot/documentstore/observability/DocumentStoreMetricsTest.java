package com.jobseekercopilot.documentstore.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class DocumentStoreMetricsTest {

    private SimpleMeterRegistry registry;
    private DocumentStoreMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DocumentStoreMetrics(registry);
    }

    @Test
    void successfulOperationPublishesBoundedCountDurationAndPayload() {
        String result = metrics.observe(
                "document",
                "create",
                DocumentType.CV,
                null,
                () -> "saved");
        metrics.recordPayload(
                "document", "stored", DocumentType.CV, null, 512);

        assertThat(result).isEqualTo("saved");
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
        assertThat(registry.get(DocumentStoreMetrics.OPERATION_DURATION)
                        .tags(
                                "resource", "document",
                                "operation", "create",
                                "outcome", "success",
                                "reason", "none",
                                "document_type", "cv",
                                "file_type", "unknown")
                        .timer()
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
                .isEqualTo(512);
    }

    @Test
    void failuresAreClassifiedWithoutExceptionOrIdentifierLabels() {
        assertThatThrownBy(() -> metrics.observe(
                        "file",
                        "retrieve_export",
                        null,
                        FileType.PDF,
                        () -> {
                            throw ResourceNotFoundException.documentFileNotFound();
                        }))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> metrics.observe(
                        "document",
                        "retrieve",
                        DocumentType.COVER_LETTER,
                        null,
                        () -> {
                            throw new DataAccessResourceFailureException(
                                    "synthetic dependency failure");
                        }))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(registry.get(DocumentStoreMetrics.OPERATION_COUNT)
                        .tags(
                                "resource", "file",
                                "operation", "retrieve_export",
                                "outcome", "failure",
                                "reason", "not_found",
                                "document_type", "unknown",
                                "file_type", "pdf")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentStoreMetrics.OPERATION_COUNT)
                        .tags(
                                "resource", "document",
                                "operation", "retrieve",
                                "outcome", "failure",
                                "reason", "dependency",
                                "document_type", "cover_letter",
                                "file_type", "unknown")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void accessAndReconciliationSignalsUseOnlyFixedDimensions() {
        metrics.recordAccessDenied(
                "/api/v1/documents/**", "authentication_required");
        metrics.recordReconciliation(
                "file", "repaired", "multiple_active");

        assertThat(registry.get(DocumentStoreMetrics.ACCESS_DENIED_COUNT)
                        .tags(
                                "route", "/api/v1/documents/**",
                                "reason", "authentication_required")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentStoreMetrics.RECONCILIATION_COUNT)
                        .tags(
                                "resource", "file",
                                "outcome", "repaired",
                                "reason", "multiple_active")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void reconciliationItemCountsUseTheSharedBoundedMetric() {
        metrics.recordReconciliation(
                "file",
                "repaired",
                "metadata_quarantined",
                3);

        assertThat(registry.get(DocumentStoreMetrics.RECONCILIATION_COUNT)
                        .tags(
                                "resource", "file",
                                "outcome", "repaired",
                                "reason", "metadata_quarantined")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void arbitraryDimensionsCollapseAndNeverReachMetricsOrLogs(
            CapturedOutput output) {
        String rawOwner = "raw-owner-alice@example.test";
        String rawDocument = "550e8400-e29b-41d4-a716-446655440000";
        metrics.observe(
                rawOwner,
                rawDocument,
                DocumentType.CV,
                FileType.DOCX,
                () -> "done");
        metrics.recordAccessDenied(rawDocument, rawOwner);

        assertThat(registry.get(DocumentStoreMetrics.OPERATION_COUNT)
                        .tags(
                                "resource", "other",
                                "operation", "other",
                                "outcome", "success",
                                "reason", "none",
                                "document_type", "cv",
                                "file_type", "docx")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(DocumentStoreMetrics.ACCESS_DENIED_COUNT)
                        .tags("route", "other", "reason", "other")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(output).doesNotContain(rawOwner, rawDocument);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getValue())
                                .doesNotContain(rawOwner, rawDocument)));
    }
}

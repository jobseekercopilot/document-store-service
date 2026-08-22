package com.jobseekercopilot.documentstore.observability;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class DocumentStoreMetrics {

    public static final String OPERATION_COUNT = "document.store.operation.count";
    public static final String OPERATION_DURATION =
            "document.store.operation.duration";
    public static final String PAYLOAD_SIZE = "document.store.payload.size";
    public static final String RECONCILIATION_COUNT =
            "document.store.reconciliation.count";
    public static final String ACCESS_DENIED_COUNT =
            "document.store.access.denied.count";

    private static final Logger log =
            LoggerFactory.getLogger(DocumentStoreMetrics.class);

    private static final Set<String> RESOURCES = Set.of("document", "file", "upload");
    private static final Set<String> OPERATIONS = Set.of(
            "create",
            "retrieve",
            "list",
            "delete",
            "activate",
            "deactivate",
            "store_export",
            "store_upload",
            "process_upload",
            "retrieve_upload",
            "cleanup_upload",
            "replace_export",
            "retrieve_export",
            "list_export");
    private static final Set<String> OUTCOMES =
            Set.of("success", "failure", "repaired");
    private static final Set<String> REASONS = Set.of(
            "none",
            "not_found",
            "invalid",
            "denied",
            "dependency",
            "conflict",
            "unexpected",
            "multiple_active",
            "reconciliation_run",
            "delete_pending",
            "prepared_committed",
            "prepared_rolled_back",
            "prepared_failed",
            "metadata_inspected",
            "metadata_quarantined",
            "metadata_inspection_failed",
            "unknown_orphan",
            "inventory_failed");
    private static final Set<String> DIRECTIONS = Set.of("stored", "retrieved");
    private static final Set<String> ACCESS_REASONS =
            Set.of("authentication_required", "access_denied");
    private static final Set<String> ROUTES = Set.of(
            "/api/v1/documents/**",
            "/api/v1/document-files/**",
            "/api/v1/application-document-uploads/**",
            "/api/v1/applications/*/documents/*/uploads",
            "/internal/system-data/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/actuator/health",
            "other",
            "unknown");

    private final MeterRegistry registry;

    public DocumentStoreMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T observe(
            String resource,
            String operation,
            DocumentType documentType,
            FileType fileType,
            Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        String safeResource = bounded(resource, RESOURCES);
        String safeOperation = bounded(operation, OPERATIONS);
        try {
            T result = action.get();
            recordOperation(
                    sample,
                    safeResource,
                    safeOperation,
                    documentType,
                    fileType,
                    "success",
                    "none");
            return result;
        } catch (RuntimeException exception) {
            recordOperation(
                    sample,
                    safeResource,
                    safeOperation,
                    documentType,
                    fileType,
                    "failure",
                    failureReason(exception));
            throw exception;
        }
    }

    public void observe(
            String resource,
            String operation,
            DocumentType documentType,
            FileType fileType,
            Runnable action) {
        observe(resource, operation, documentType, fileType, () -> {
            action.run();
            return null;
        });
    }

    public void recordPayload(
            String resource,
            String direction,
            DocumentType documentType,
            FileType fileType,
            long bytes) {
        DistributionSummary.builder(PAYLOAD_SIZE)
                .baseUnit("bytes")
                .description("Document content or exported file payload size")
                .tags(
                        "resource", bounded(resource, RESOURCES),
                        "direction", bounded(direction, DIRECTIONS),
                        "document_type", enumTag(documentType),
                        "file_type", enumTag(fileType))
                .register(registry)
                .record(Math.max(0, bytes));
    }

    public void recordReconciliation(
            String resource, String outcome, String reason) {
        recordReconciliation(resource, outcome, reason, 1);
    }

    public void recordReconciliation(
            String resource,
            String outcome,
            String reason,
            long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(RECONCILIATION_COUNT)
                .description("Document consistency anomalies reconciled by a bounded operation")
                .tags(
                        "resource", bounded(resource, RESOURCES),
                        "outcome", bounded(outcome, OUTCOMES),
                        "reason", bounded(reason, REASONS))
                .register(registry)
                .increment(count);
    }

    public void recordAccessDenied(String route, String reason) {
        Counter.builder(ACCESS_DENIED_COUNT)
                .description("Authentication and authorization denials by safe route family")
                .tags(
                        "route", bounded(route, ROUTES),
                        "reason", bounded(reason, ACCESS_REASONS))
                .register(registry)
                .increment();
    }

    private void recordOperation(
            Timer.Sample sample,
            String resource,
            String operation,
            DocumentType documentType,
            FileType fileType,
            String outcome,
            String reason) {
        String safeOutcome = bounded(outcome, OUTCOMES);
        String safeReason = bounded(reason, REASONS);
        String safeDocumentType = enumTag(documentType);
        String safeFileType = enumTag(fileType);
        String[] tags = {
                "resource", resource,
                "operation", operation,
                "outcome", safeOutcome,
                "reason", safeReason,
                "document_type", safeDocumentType,
                "file_type", safeFileType
        };

        Counter.builder(OPERATION_COUNT)
                .description("Document Store operations by bounded outcome")
                .tags(tags)
                .register(registry)
                .increment();
        long durationNanos = sample.stop(Timer.builder(OPERATION_DURATION)
                .description("Document Store operation duration")
                .tags(tags)
                .register(registry));

        if ("success".equals(safeOutcome)) {
            log.info(
                    "Document operation completed resource={} operation={} outcome={} durationMs={}",
                    resource,
                    operation,
                    safeOutcome,
                    durationNanos / 1_000_000);
        } else {
            log.warn(
                    "Document operation completed resource={} operation={} outcome={} reason={} durationMs={}",
                    resource,
                    operation,
                    safeOutcome,
                    safeReason,
                    durationNanos / 1_000_000);
        }
    }

    private String failureReason(RuntimeException exception) {
        if (exception instanceof ResourceNotFoundException) {
            return "not_found";
        }
        if (exception instanceof AccessDeniedException) {
            return "denied";
        }
        if (exception instanceof DataAccessException) {
            return "dependency";
        }
        if (exception instanceof ObjectStorageException) {
            return "dependency";
        }
        if (exception instanceof OperationConflictException) {
            return "conflict";
        }
        if (exception instanceof IllegalArgumentException) {
            return "invalid";
        }
        return "unexpected";
    }

    private String enumTag(Enum<?> value) {
        return value == null
                ? "unknown"
                : value.name().toLowerCase(Locale.ROOT);
    }

    private String bounded(String value, Set<String> allowed) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "other";
    }
}

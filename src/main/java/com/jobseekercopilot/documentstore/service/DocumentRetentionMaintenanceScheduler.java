package com.jobseekercopilot.documentstore.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "document-store.retention.maintenance-enabled",
        havingValue = "true")
public class DocumentRetentionMaintenanceScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentRetentionMaintenanceScheduler.class);

    private final DocumentRetentionMaintenanceService maintenanceService;
    private final MeterRegistry meterRegistry;

    @Scheduled(
            initialDelayString =
                    "${document-store.retention.maintenance-fixed-delay-ms:86400000}",
            fixedDelayString =
                    "${document-store.retention.maintenance-fixed-delay-ms:86400000}")
    public void run() {
        try {
            DocumentRetentionMaintenanceReport report =
                    maintenanceService.purgeExpiredAuditHistory();
            meterRegistry.counter(
                            "document_store_retention_maintenance_runs_total",
                            "outcome",
                            "completed")
                    .increment();
            log.info(
                    "Document retention maintenance completed "
                            + "completedStorageOperationsPurged={} lifecycleEventsPurged={} activityEventsPurged={}",
                    report.completedStorageOperationsPurged(),
                    report.lifecycleEventsPurged(),
                    report.activityEventsPurged());
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "document_store_retention_maintenance_runs_total",
                            "outcome",
                            "failed")
                    .increment();
            log.error("Document retention maintenance failed");
        }
    }
}

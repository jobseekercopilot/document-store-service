package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentStorageReconciliationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "document-store.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DocumentStorageReconciliationScheduler implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentStorageReconciliationScheduler.class);

    private final DocumentStorageReconciler reconciler;
    private final DocumentStorageReconciliationProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isRunOnStartup()) {
            runSafely();
        }
    }

    @Scheduled(
            initialDelayString = "${document-store.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${document-store.reconciliation.fixed-delay-ms:300000}")
    public void scheduledReconciliation() {
        runSafely();
    }

    private void runSafely() {
        try {
            reconciler.reconcile();
            recordRun("completed");
        } catch (RuntimeException exception) {
            recordRun("failed");
            log.error("Document storage reconciliation pass failed");
        }
    }

    private void recordRun(String outcome) {
        meterRegistry.counter(
                        "document_store_reconciliation_runs_total",
                        "outcome",
                        outcome)
                .increment();
    }
}

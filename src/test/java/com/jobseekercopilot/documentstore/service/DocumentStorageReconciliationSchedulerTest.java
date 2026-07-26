package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jobseekercopilot.documentstore.config.DocumentStorageReconciliationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DocumentStorageReconciliationSchedulerTest {

    private static final DefaultApplicationArguments NO_ARGUMENTS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void startupTriggerIsExplicitAndRecordsCompletion() {
        var reconciler = mock(DocumentStorageReconciler.class);
        var properties = new DocumentStorageReconciliationProperties();
        var registry = new SimpleMeterRegistry();
        var scheduler =
                new DocumentStorageReconciliationScheduler(reconciler, properties, registry);

        scheduler.run(NO_ARGUMENTS);
        verifyNoInteractions(reconciler);

        properties.setRunOnStartup(true);
        scheduler.run(NO_ARGUMENTS);

        verify(reconciler).reconcile();
        assertThat(registry.counter(
                                "document_store_reconciliation_runs_total",
                                "outcome",
                                "completed")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void scheduledFailureIsContainedAndObservable() {
        var reconciler = mock(DocumentStorageReconciler.class);
        doThrow(new IllegalStateException("injected failure")).when(reconciler).reconcile();
        var registry = new SimpleMeterRegistry();
        var scheduler = new DocumentStorageReconciliationScheduler(
                reconciler, new DocumentStorageReconciliationProperties(), registry);

        scheduler.scheduledReconciliation();

        assertThat(registry.counter(
                                "document_store_reconciliation_runs_total",
                                "outcome",
                                "failed")
                        .count())
                .isEqualTo(1);
    }
}

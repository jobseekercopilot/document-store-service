package com.jobseekercopilot.documentstore.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentPermanentErasureScheduler {

    private final DocumentPermanentErasureService service;

    @Scheduled(
            initialDelayString = "${document-store.retention.permanent-erasure-fixed-delay-ms:300000}",
            fixedDelayString = "${document-store.retention.permanent-erasure-fixed-delay-ms:300000}")
    public void reconcile() {
        service.reconcileBatch();
    }
}

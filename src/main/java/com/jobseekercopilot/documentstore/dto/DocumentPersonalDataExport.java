package com.jobseekercopilot.documentstore.dto;

import java.time.Instant;
import java.util.List;

public record DocumentPersonalDataExport(
        String schemaVersion,
        Instant generatedAt,
        List<GeneratedDocumentResponse> documents,
        List<DocumentFileResponse> files,
        List<DocumentLifecycleEventResponse> lifecycleEvents) {
}

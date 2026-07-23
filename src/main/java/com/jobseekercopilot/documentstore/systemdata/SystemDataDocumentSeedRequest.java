package com.jobseekercopilot.documentstore.systemdata;

import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;

import java.util.List;

public record SystemDataDocumentSeedRequest(
        String scenarioId,
        String userId,
        List<GeneratedDocument> documents,
        List<ExportedDocumentFile> files) {
}

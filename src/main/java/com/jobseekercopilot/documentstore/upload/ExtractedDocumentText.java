package com.jobseekercopilot.documentstore.upload;

import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;

public record ExtractedDocumentText(
        String text, DocumentExtractionState state, String sha256) {
}

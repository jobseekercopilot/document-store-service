package com.jobseekercopilot.documentstore.dto;

public record DocumentFileDownload(
        String fileName,
        String mimeType,
        byte[] content) {
}

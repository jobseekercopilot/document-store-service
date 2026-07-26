package com.jobseekercopilot.documentstore.systemdata;

import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import java.time.LocalDateTime;
import java.util.UUID;

public record SystemDataDocumentFileSeed(
        UUID id,
        UUID generatedDocumentId,
        FileType fileType,
        String fileName,
        String mimeType,
        FileSource source,
        Boolean active,
        Integer version,
        byte[] fileContent,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

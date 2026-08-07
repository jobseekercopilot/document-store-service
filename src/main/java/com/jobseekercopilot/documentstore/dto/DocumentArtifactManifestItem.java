package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Safe metadata for one exact retained representation of a document version")
public record DocumentArtifactManifestItem(
        UUID artifactId,
        DocumentArtifactRole role,
        FileType format,
        FileSource source,
        DocumentArtifactAvailability availability,
        long size,
        OffsetDateTime storedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

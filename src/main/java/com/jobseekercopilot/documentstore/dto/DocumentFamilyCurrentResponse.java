package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Replay-stable result of an explicit family current-pointer command")
public record DocumentFamilyCurrentResponse(
        UUID commandId,
        UUID documentFamilyId,
        UUID currentDocumentId,
        int currentVersion,
        OffsetDateTime changedAt) {
}

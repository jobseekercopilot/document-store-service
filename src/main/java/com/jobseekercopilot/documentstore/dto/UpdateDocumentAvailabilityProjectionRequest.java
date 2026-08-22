package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import java.time.LocalDateTime;

public record UpdateDocumentAvailabilityProjectionRequest(
        DocumentRetentionState availability,
        String unavailableReason,
        LocalDateTime occurredAt) {
}

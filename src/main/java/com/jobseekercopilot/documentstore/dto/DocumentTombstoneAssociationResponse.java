package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentApplicationAssociationState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentTombstoneAssociationResponse(
        UUID applicationId,
        DocumentType documentType,
        DocumentApplicationAssociationState associationState,
        String applicationStatus,
        OffsetDateTime frozenAt) {
}

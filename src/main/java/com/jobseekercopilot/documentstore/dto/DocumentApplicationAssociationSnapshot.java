package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentApplicationAssociationState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentApplicationAssociationSnapshot(
        UUID applicationId,
        DocumentType documentType,
        DocumentApplicationAssociationState associationState,
        String applicationStatus,
        LocalDateTime frozenAt) {
}

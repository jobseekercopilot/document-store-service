package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentActivityEventResponse(
        UUID id,
        DocumentActivityType eventType,
        UUID documentId,
        UUID documentFamilyId,
        DocumentType documentType,
        DocumentSourceType source,
        int version,
        String result,
        OffsetDateTime occurredAt) {
}

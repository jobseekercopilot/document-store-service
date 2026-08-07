package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.DocumentApplicationAssociationsSnapshot;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import java.time.LocalDateTime;
import java.util.UUID;

public interface ApplicationAssociationClient {
    DocumentApplicationAssociationsSnapshot associations(
            String ownerId,
            UUID documentId);

    void updateAvailability(
            String ownerId,
            UUID documentId,
            DocumentRetentionState availability,
            String unavailableReason,
            LocalDateTime occurredAt);
}

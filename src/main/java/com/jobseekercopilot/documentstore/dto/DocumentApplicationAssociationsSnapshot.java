package com.jobseekercopilot.documentstore.dto;

import java.util.List;
import java.util.UUID;

public record DocumentApplicationAssociationsSnapshot(
        UUID documentId,
        int associationCount,
        List<DocumentApplicationAssociationSnapshot> associations) {
}

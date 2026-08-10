package com.jobseekercopilot.documentstore.dto;

public record AccountDocumentDeletionResponse(
        int recoverablyDeleted,
        int alreadyDeleted,
        int legalHoldRetained,
        int purgedTombstones) {
}

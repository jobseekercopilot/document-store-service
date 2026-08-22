package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScopeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PermanentErasureRecoveryJournalRecord(
        String schemaVersion,
        UUID operationId,
        String ownerId,
        List<UUID> documentIds,
        List<Scope> objectScopes,
        String requestSha256,
        String approvalReferenceSha256,
        String policyVersion,
        String backupRetentionPolicyVersion,
        int backupRetentionDays,
        OffsetDateTime createdAt) {

    public static final String SCHEMA_VERSION =
            "document-permanent-erasure-recovery-journal.v1";

    public record Scope(
            DocumentOwnerErasureScopeType scopeType,
            UUID documentId,
            String storageScope) {
    }
}

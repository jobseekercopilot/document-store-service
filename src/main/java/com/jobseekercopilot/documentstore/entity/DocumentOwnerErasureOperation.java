package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_owner_erasure_operations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentOwnerErasureOperation {

    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "owner_fingerprint", nullable = false, length = 64, updatable = false)
    private String ownerFingerprint;

    @Column(name = "fingerprint_key_verifier", nullable = false, length = 64, updatable = false)
    private String fingerprintKeyVerifier;

    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;

    @Column(name = "approval_reference_sha256", nullable = false, length = 64, updatable = false)
    private String approvalReferenceSha256;

    @Column(name = "operator_id", nullable = false, length = 64, updatable = false)
    private String operatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentOwnerErasureState state;

    @Column(name = "document_count", nullable = false, updatable = false)
    private int documentCount;

    @Column(name = "object_scope_count", nullable = false)
    private int objectScopeCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "policy_version", nullable = false, length = 128, updatable = false)
    private String policyVersion;

    @Column(name = "backup_retention_policy_version", nullable = false, length = 128, updatable = false)
    private String backupRetentionPolicyVersion;

    @Column(name = "backup_retention_days", nullable = false, updatable = false)
    private int backupRetentionDays;

    @Column(name = "journal_required", nullable = false)
    private boolean journalRequired;

    @Column(name = "journal_schema_version", length = 80)
    private String journalSchemaVersion;

    @Column(name = "journal_object_key", length = 512)
    private String journalObjectKey;

    @Column(name = "journal_object_version", length = 256)
    private String journalObjectVersion;

    @Column(name = "journal_content_sha256", length = 64)
    private String journalContentSha256;

    @Column(name = "journal_recorded_at")
    private LocalDateTime journalRecordedAt;

    @Column(name = "restore_replay_id")
    private UUID restoreReplayId;

    @Column(name = "restore_replay_evidence_sha256", length = 64)
    private String restoreReplayEvidenceSha256;

    @Column(name = "restore_replay_requested_by", length = 64)
    private String restoreReplayRequestedBy;

    @Column(name = "restore_replay_requested_at")
    private LocalDateTime restoreReplayRequestedAt;

    @Column(name = "restore_replay_object_erased_at")
    private LocalDateTime restoreReplayObjectErasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "live_data_erased_at")
    private LocalDateTime liveDataErasedAt;

    @Column(name = "backup_retention_until")
    private LocalDateTime backupRetentionUntil;

    @Column(name = "backup_expiry_evidence_sha256", length = 64)
    private String backupExpiryEvidenceSha256;

    @Column(name = "backup_expiry_attested_by", length = 64)
    private String backupExpiryAttestedBy;

    @Column(name = "backup_expiry_attested_at")
    private LocalDateTime backupExpiryAttestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (operationId == null) {
            operationId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (state == null) {
            state = DocumentOwnerErasureState.JOURNAL_PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

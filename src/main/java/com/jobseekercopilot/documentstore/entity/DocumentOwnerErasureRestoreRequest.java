package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "document_owner_erasure_restore_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentOwnerErasureRestoreRequest {

    @Id
    @Column(name = "restore_replay_id")
    private UUID restoreReplayId;

    @Column(name = "operation_id", nullable = false, unique = true, updatable = false)
    private UUID operationId;

    @Column(name = "owner_fingerprint", nullable = false, length = 64, updatable = false)
    private String ownerFingerprint;

    @Column(name = "fingerprint_key_verifier", nullable = false, length = 64, updatable = false)
    private String fingerprintKeyVerifier;

    @Column(name = "evidence_sha256", nullable = false, length = 64, updatable = false)
    private String evidenceSha256;

    @Column(name = "requested_by", nullable = false, length = 64, updatable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}

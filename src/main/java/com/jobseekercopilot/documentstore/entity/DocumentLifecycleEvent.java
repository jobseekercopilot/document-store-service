package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_lifecycle_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentLifecycleEvent {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_family_id", nullable = false, updatable = false)
    private UUID documentFamilyId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DocumentLifecycleAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, updatable = false)
    private DocumentRetentionState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, updatable = false)
    private DocumentRetentionState toState;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "case_reference", updatable = false, length = 128)
    private String caseReference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "retention_expires_at", nullable = false, updatable = false)
    private LocalDateTime retentionExpiresAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}

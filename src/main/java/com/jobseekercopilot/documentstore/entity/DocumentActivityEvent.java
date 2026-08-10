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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "document_activity_events")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DocumentActivityEvent {

    @Id
    @Builder.Default
    @Column(updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "event_key", nullable = false, updatable = false, unique = true, length = 180)
    private String eventKey;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 48)
    private DocumentActivityType eventType;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_family_id", nullable = false, updatable = false)
    private UUID documentFamilyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, updatable = false, length = 24)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 24)
    private DocumentSourceType sourceType;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, updatable = false, length = 48)
    private String result;

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

package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Embedded;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generated_documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratedDocument {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String jobId;

    private String applicationId;

    @Column(nullable = false)
    private UUID documentFamilyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DocumentLifecycleState lifecycleState = DocumentLifecycleState.DRAFT;

    @Column(length = 64)
    private String contentSha256;

    @Embedded
    private GenerationProvenance generationProvenance;

    private LocalDateTime approvedAt;

    private String approvedBy;

    @Column(name = "current_slot")
    private Short currentSlot;

    @Column(name = "operation_key", length = 128)
    private String operationKey;

    @Column(name = "request_sha256", length = 64)
    private String requestSha256;

    private String originalFilename;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DocumentSourceType sourceType = DocumentSourceType.GENERATED;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (documentFamilyId == null) {
            documentFamilyId = id;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (version == null) {
            version = 1;
        }
        if (sourceType == null) {
            sourceType = DocumentSourceType.GENERATED;
        }
        if (lifecycleState == null) {
            lifecycleState = DocumentLifecycleState.DRAFT;
        }
        if (lifecycleState == DocumentLifecycleState.DRAFT) {
            active = false;
        }
        syncCurrentSlot();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        syncCurrentSlot();
    }

    private void syncCurrentSlot() {
        currentSlot = active ? (short) 1 : null;
    }
}

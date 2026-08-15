package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "application_document_uploads")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationDocumentUpload {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String ownerId;

    @Column(nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestSha256;

    @Column(nullable = false, updatable = false)
    private String jobId;

    @Column(nullable = false, updatable = false)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FileType fileType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationDocumentUploadState state;

    @Column(nullable = false, updatable = false, length = 64)
    private String originalSha256;

    @Column(nullable = false, updatable = false)
    private long originalSize;

    @Column(length = 64)
    private String extractedTextSha256;

    @Enumerated(EnumType.STRING)
    private DocumentExtractionState extractionState;

    @Column(length = 512)
    private String quarantineKey;

    private UUID documentId;

    private UUID artifactId;

    @Column(length = 64)
    private String failureCode;

    @Column(length = 255)
    private String failureMessage;

    @Column(length = 32)
    private String scannerEngine;

    @Column(length = 64)
    private String scannerVersion;

    private LocalDateTime scannerSignatureAt;

    private LocalDateTime processingStartedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    @Version
    private long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (state == null) {
            state = ApplicationDocumentUploadState.RECEIVED;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

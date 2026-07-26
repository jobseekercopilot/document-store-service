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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_storage_operations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentStorageOperation {

    @Id
    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private String ownerId;

    @Column(name = "generated_document_id", nullable = false, updatable = false)
    private UUID generatedDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, updatable = false)
    private FileType fileType;

    @Column(name = "file_version", nullable = false, updatable = false)
    private int fileVersion;

    @Column(name = "storage_key", nullable = false, unique = true, length = 512, updatable = false)
    private String storageKey;

    @Column(name = "operation_key", length = 128, updatable = false)
    private String operationKey;

    @Column(name = "request_sha256", nullable = false, length = 64, updatable = false)
    private String requestSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StorageOperationState state = StorageOperationState.PREPARED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (fileId == null) {
            fileId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

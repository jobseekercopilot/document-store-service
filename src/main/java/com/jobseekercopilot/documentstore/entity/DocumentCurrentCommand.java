package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "document_current_commands")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentCurrentCommand {
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String ownerId;

    @Column(nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestSha256;

    @Column(nullable = false, updatable = false)
    private UUID documentFamilyId;

    @Column(nullable = false, updatable = false)
    private UUID currentDocumentId;

    @Column(nullable = false, updatable = false)
    private int currentVersion;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

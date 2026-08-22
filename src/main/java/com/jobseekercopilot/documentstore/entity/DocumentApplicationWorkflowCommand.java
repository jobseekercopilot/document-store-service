package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "document_application_workflow_commands")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentApplicationWorkflowCommand {

    @Id
    private UUID operationId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false, length = 32)
    private String commandType;

    @Column(nullable = false, length = 64)
    private String requestSha256;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
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

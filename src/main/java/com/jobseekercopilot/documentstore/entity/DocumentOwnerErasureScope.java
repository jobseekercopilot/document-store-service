package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_owner_erasure_scopes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentOwnerErasureScope {

    @Id
    private UUID id;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, updatable = false)
    private DocumentOwnerErasureScopeType scopeType;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "storage_scope", nullable = false, length = 512, updatable = false)
    private String storageScope;

    @Column(name = "erased_at")
    private LocalDateTime erasedAt;
}

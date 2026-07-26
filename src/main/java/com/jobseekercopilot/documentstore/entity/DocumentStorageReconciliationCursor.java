package com.jobseekercopilot.documentstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_storage_reconciliation_cursors")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentStorageReconciliationCursor {

    @Id
    @Column(name = "cursor_name", length = 64)
    private String cursorName;

    @Column(name = "after_key", length = 512)
    private String afterKey;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}

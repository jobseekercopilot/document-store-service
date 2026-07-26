package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentLifecycleEventRepository
        extends JpaRepository<DocumentLifecycleEvent, UUID> {

    List<DocumentLifecycleEvent> findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
            UUID documentId, String ownerId);

    boolean existsByDocumentIdAndOwnerIdAndAction(
            UUID documentId,
            String ownerId,
            com.jobseekercopilot.documentstore.entity.DocumentLifecycleAction action);

    List<DocumentLifecycleEvent> findByRetentionExpiresAtBeforeOrderByRetentionExpiresAtAsc(
            LocalDateTime cutoff, Pageable pageable);
}

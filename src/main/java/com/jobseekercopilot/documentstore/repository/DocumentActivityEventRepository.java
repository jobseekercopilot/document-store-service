package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentActivityEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentActivityEventRepository
        extends JpaRepository<DocumentActivityEvent, UUID> {

    Page<DocumentActivityEvent> findByOwnerIdOrderByOccurredAtAscIdAsc(
            String ownerId, Pageable pageable);

    boolean existsByEventKey(String eventKey);

    java.util.List<DocumentActivityEvent>
            findByRetentionExpiresAtBeforeOrderByRetentionExpiresAtAsc(
                    LocalDateTime cutoff, Pageable pageable);
}

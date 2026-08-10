package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentCurrentCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentCurrentCommandRepository
        extends JpaRepository<DocumentCurrentCommand, UUID> {
    Optional<DocumentCurrentCommand> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    long countByOwnerId(String ownerId);

    void deleteByOwnerId(String ownerId);
}

package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentApplicationWorkflowCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentApplicationWorkflowCommandRepository
        extends JpaRepository<DocumentApplicationWorkflowCommand, UUID> {

    Optional<DocumentApplicationWorkflowCommand>
            findByOperationIdAndOwnerId(UUID operationId, String ownerId);

    long countByOwnerId(String ownerId);

    void deleteByOwnerId(String ownerId);
}

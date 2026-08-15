package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentOwnerErasureScopeRepository
        extends JpaRepository<DocumentOwnerErasureScope, UUID> {

    List<DocumentOwnerErasureScope> findByOperationIdOrderByStorageScopeAsc(
            UUID operationId);

    long countByOperationIdAndErasedAtIsNull(UUID operationId);

    java.util.Optional<DocumentOwnerErasureScope> findByIdAndOperationId(
            UUID id, UUID operationId);

    void deleteByOperationId(UUID operationId);
}

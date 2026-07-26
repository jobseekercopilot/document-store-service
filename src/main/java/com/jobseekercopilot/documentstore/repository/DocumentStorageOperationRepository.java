package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentStorageOperation;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentStorageOperationRepository
        extends JpaRepository<DocumentStorageOperation, UUID> {

    Optional<DocumentStorageOperation> findByOwnerIdAndOperationKey(
            String ownerId, String operationKey);

    boolean existsByStorageKeyAndState(String storageKey, StorageOperationState state);

    List<DocumentStorageOperation> findByStateAndUpdatedAtBeforeOrderByStorageKeyAsc(
            StorageOperationState state,
            LocalDateTime cutoff,
            Pageable pageable);
    List<DocumentStorageOperation>
            findByStateAndUpdatedAtBeforeAndStorageKeyGreaterThanOrderByStorageKeyAsc(
                    StorageOperationState state,
                    LocalDateTime cutoff,
                    String afterKey,
                    Pageable pageable);

    @Query("""
            select max(operation.fileVersion)
            from DocumentStorageOperation operation
            where operation.generatedDocumentId = :documentId
              and operation.fileType = :fileType
            """)
    Integer findHighestReservedVersion(
            @Param("documentId") UUID documentId,
            @Param("fileType") FileType fileType);
}

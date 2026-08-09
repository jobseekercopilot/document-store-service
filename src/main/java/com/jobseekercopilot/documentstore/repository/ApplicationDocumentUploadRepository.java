package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationDocumentUploadRepository
        extends JpaRepository<ApplicationDocumentUpload, UUID> {

    Optional<ApplicationDocumentUpload> findByOwnerIdAndIdempotencyKey(
            String ownerId, String idempotencyKey);

    Optional<ApplicationDocumentUpload> findByIdAndOwnerId(
            UUID id, String ownerId);

    List<ApplicationDocumentUpload> findByOwnerId(String ownerId);

    long countByOwnerId(String ownerId);

    void deleteByOwnerId(String ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApplicationDocumentUpload upload "
            + "set upload.processingStartedAt = :startedAt, upload.updatedAt = :startedAt "
            + "where upload.id = :id and upload.ownerId = :ownerId "
            + "and upload.processingStartedAt is null and upload.state in :states")
    int claimProcessing(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("states") Collection<ApplicationDocumentUploadState> states);

    long countByOwnerIdAndCreatedAtAfter(
            String ownerId, LocalDateTime createdAfter);

    @Query("select coalesce(sum(upload.originalSize), 0) from ApplicationDocumentUpload upload "
            + "where upload.ownerId = :ownerId and upload.state in :states")
    long sumOriginalSizeByOwnerIdAndStateIn(
            @Param("ownerId") String ownerId,
            @Param("states") Collection<ApplicationDocumentUploadState> states);

    List<ApplicationDocumentUpload> findByStateInAndUpdatedAtBeforeAndQuarantineKeyIsNotNullOrderByUpdatedAtAsc(
            Collection<ApplicationDocumentUploadState> states,
            LocalDateTime updatedBefore,
            Pageable pageable);
}

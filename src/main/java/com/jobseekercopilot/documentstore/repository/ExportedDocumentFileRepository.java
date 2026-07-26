package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportedDocumentFileRepository extends JpaRepository<ExportedDocumentFile, UUID> {
    List<ExportedDocumentFile> findByGeneratedDocumentIdOrderByCreatedAtDesc(UUID generatedDocumentId);
    List<ExportedDocumentFile> findByGeneratedDocumentIdIn(List<UUID> generatedDocumentIds);
    void deleteByGeneratedDocumentIdIn(List<UUID> generatedDocumentIds);
    Optional<ExportedDocumentFile> findByIdAndGeneratedDocument_UserId(UUID id, String userId);
    Optional<ExportedDocumentFile> findByOwnerIdAndOperationKey(String ownerId, String operationKey);
    List<ExportedDocumentFile> findByGeneratedDocumentIdAndGeneratedDocument_UserIdOrderByCreatedAtDesc(
            UUID generatedDocumentId,
            String userId);
    List<ExportedDocumentFile> findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndActiveTrueOrderByUpdatedAtDesc(
            UUID generatedDocumentId,
            String userId);
    List<ExportedDocumentFile> findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
            UUID generatedDocumentId,
            String userId,
            FileType fileType);
    Optional<ExportedDocumentFile> findFirstByGeneratedDocumentIdAndFileTypeAndActiveTrueOrderByUpdatedAtDesc(
            UUID generatedDocumentId, FileType fileType);
    Optional<ExportedDocumentFile> findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
            UUID generatedDocumentId, FileType fileType);
    boolean existsByStorageKey(String storageKey);
    List<ExportedDocumentFile> findByStorageStatusAndUpdatedAtBeforeOrderByStorageKeyAsc(
            ObjectStorageStatus storageStatus,
            LocalDateTime cutoff,
            Pageable pageable);
    List<ExportedDocumentFile>
            findByStorageStatusAndUpdatedAtBeforeAndStorageKeyGreaterThanOrderByStorageKeyAsc(
                    ObjectStorageStatus storageStatus,
                    LocalDateTime cutoff,
                    String afterKey,
                    Pageable pageable);
}

package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {

    List<GeneratedDocument> findByUserId(String userId);

    void deleteByUserId(String userId);

    List<GeneratedDocument> findByJobId(String jobId);

    List<GeneratedDocument> findByUserIdAndJobId(String userId, String jobId);

    Optional<GeneratedDocument> findByIdAndUserId(UUID id, String userId);

    List<GeneratedDocument> findByDocumentType(DocumentType documentType);

    List<GeneratedDocument> findByApplicationIdAndDocumentTypeAndUserIdOrderByVersionDesc(
            String applicationId,
            DocumentType documentType,
            String userId);

    List<GeneratedDocument> findByApplicationIdAndDocumentTypeAndActiveTrueAndUserId(
            String applicationId,
            DocumentType documentType,
            String userId);

    List<GeneratedDocument> findByApplicationIdAndUserId(String applicationId, String userId);
}

package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Optional<GeneratedDocument> findByUserIdAndOperationKey(String userId, String operationKey);

    List<GeneratedDocument> findByDocumentType(DocumentType documentType);

    Optional<GeneratedDocument> findFirstByDocumentFamilyIdAndUserIdOrderByVersionDesc(
            UUID documentFamilyId,
            String userId);

    List<GeneratedDocument> findByDocumentFamilyIdAndActiveTrueAndUserId(
            UUID documentFamilyId,
            String userId);

    Optional<GeneratedDocument> findFirstByDocumentFamilyIdAndActiveTrueAndUserId(
            UUID documentFamilyId,
            String userId);

    List<GeneratedDocument> findByDocumentFamilyIdAndUserIdOrderByVersionDesc(
            UUID documentFamilyId,
            String userId);

    long countByDocumentFamilyIdAndUserId(UUID documentFamilyId, String userId);

    @Query(
            value = """
                    select document
                    from GeneratedDocument document
                    where document.userId = :ownerId
                      and document.version = (
                          select max(version.version)
                          from GeneratedDocument version
                          where version.userId = document.userId
                            and version.documentFamilyId = document.documentFamilyId
                      )
                    order by document.updatedAt desc, document.documentFamilyId asc
                    """,
            countQuery = """
                    select count(distinct document.documentFamilyId)
                    from GeneratedDocument document
                    where document.userId = :ownerId
                    """)
    Page<GeneratedDocument> findLatestFamilyVersions(
            @Param("ownerId") String ownerId,
            Pageable pageable);

    Optional<GeneratedDocument> findFirstByApplicationIdAndDocumentTypeAndUserIdOrderByVersionDesc(
            String applicationId,
            DocumentType documentType,
            String userId);

    List<GeneratedDocument> findByApplicationIdAndDocumentTypeAndActiveTrueAndUserId(
            String applicationId,
            DocumentType documentType,
            String userId);

    List<GeneratedDocument> findByApplicationIdAndUserId(String applicationId, String userId);
}

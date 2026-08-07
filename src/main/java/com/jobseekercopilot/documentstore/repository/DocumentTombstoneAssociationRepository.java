package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentTombstoneAssociation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTombstoneAssociationRepository
        extends JpaRepository<DocumentTombstoneAssociation, UUID> {
    List<DocumentTombstoneAssociation> findByDocumentIdOrderByApplicationIdAsc(
            UUID documentId);

    void deleteByDocumentIdIn(List<UUID> documentIds);
}

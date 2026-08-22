package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentStorageReconciliationCursor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentStorageReconciliationCursorRepository
        extends JpaRepository<DocumentStorageReconciliationCursor, String> {
}

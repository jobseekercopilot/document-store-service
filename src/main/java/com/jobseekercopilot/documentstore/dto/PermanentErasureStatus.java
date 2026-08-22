package com.jobseekercopilot.documentstore.dto;

public enum PermanentErasureStatus {
    JOURNAL_PENDING,
    OBJECT_ERASURE_PENDING,
    RESTORE_JOURNAL_READ_PENDING,
    RESTORE_REPLAY_PENDING,
    BACKUP_RETENTION_PENDING,
    COMPLETED
}

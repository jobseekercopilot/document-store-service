package com.jobseekercopilot.documentstore.entity;

public enum ApplicationDocumentUploadState {
    RECEIVED,
    QUARANTINED,
    SCANNING,
    SCANNED_CLEAN,
    EXTRACTING,
    READY,
    REJECTED,
    FAILED,
    SCAN_UNAVAILABLE;

    public boolean terminal() {
        return this == READY
                || this == REJECTED
                || this == FAILED
                || this == SCAN_UNAVAILABLE;
    }
}

package com.jobseekercopilot.documentstore.service;

public record DocumentStorageReconciliationReport(
        int deletePendingCompleted,
        int deletePendingFailed,
        int preparedCommitted,
        int preparedRolledBack,
        int preparedFailed,
        int availableInspected,
        int metadataQuarantined,
        int metadataInspectionFailed,
        int unknownOrphansDetected,
        int inventoryFailed) {
}

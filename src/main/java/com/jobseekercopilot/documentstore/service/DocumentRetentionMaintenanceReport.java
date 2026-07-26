package com.jobseekercopilot.documentstore.service;

public record DocumentRetentionMaintenanceReport(
        int completedStorageOperationsPurged,
        int lifecycleEventsPurged) {
}

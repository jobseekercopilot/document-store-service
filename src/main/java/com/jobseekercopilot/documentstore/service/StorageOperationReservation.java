package com.jobseekercopilot.documentstore.service;

import java.util.UUID;

public record StorageOperationReservation(
        UUID fileId,
        int fileVersion,
        String storageKey) {
}

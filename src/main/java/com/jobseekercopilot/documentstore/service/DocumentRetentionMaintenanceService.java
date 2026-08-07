package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetentionMaintenanceService {

    private final DocumentStorageOperationRepository operationRepository;
    private final DocumentLifecycleEventRepository eventRepository;
    private final DocumentActivityEventRepository activityEventRepository;
    private final DocumentRetentionProperties properties;

    @Transactional
    public DocumentRetentionMaintenanceReport purgeExpiredAuditHistory() {
        properties.requireApprovedMaintenancePolicy();
        LocalDateTime now = LocalDateTime.now();
        PageRequest batch = PageRequest.of(0, properties.getMaintenanceBatchSize());
        var completedOperations =
                operationRepository.findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        List.of(
                                StorageOperationState.COMMITTED,
                                StorageOperationState.ROLLED_BACK),
                        now.minusDays(properties.getCompletedOperationDays()),
                        batch);
        var lifecycleEvents =
                eventRepository.findByRetentionExpiresAtBeforeOrderByRetentionExpiresAtAsc(
                        now, batch);
        var activityEvents = activityEventRepository
                .findByRetentionExpiresAtBeforeOrderByRetentionExpiresAtAsc(
                        now, batch);
        operationRepository.deleteAllInBatch(completedOperations);
        eventRepository.deleteAllInBatch(lifecycleEvents);
        activityEventRepository.deleteAllInBatch(activityEvents);
        return new DocumentRetentionMaintenanceReport(
                completedOperations.size(),
                lifecycleEvents.size(),
                activityEvents.size());
    }
}

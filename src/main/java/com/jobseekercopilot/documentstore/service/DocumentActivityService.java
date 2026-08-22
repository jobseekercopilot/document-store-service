package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.dto.DocumentActivityEventResponse;
import com.jobseekercopilot.documentstore.dto.DocumentActivityPageResponse;
import com.jobseekercopilot.documentstore.entity.DocumentActivityEvent;
import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.DocumentActivityEventRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentActivityService {
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentActivityEventRepository repository;
    private final DocumentRetentionProperties retentionProperties;
    private final DocumentOwnerErasureGuard ownerErasureGuard;

    @Transactional
    public void recordOnce(
            String eventKey,
            DocumentActivityType eventType,
            GeneratedDocument document,
            String result,
            LocalDateTime occurredAt) {
        ownerErasureGuard.requireWritable(document.getUserId());
        if (repository.existsByEventKey(eventKey)) {
            return;
        }
        try {
            repository.saveAndFlush(event(
                    eventKey, eventType, document, result, occurredAt));
        } catch (DataIntegrityViolationException race) {
            if (!repository.existsByEventKey(eventKey)) {
                throw race;
            }
        }
    }

    @Transactional
    public void record(
            DocumentActivityType eventType,
            GeneratedDocument document,
            String result,
            LocalDateTime occurredAt) {
        ownerErasureGuard.requireWritable(document.getUserId());
        repository.saveAndFlush(event(
                eventType.name() + ":" + UUID.randomUUID(),
                eventType,
                document,
                result,
                occurredAt));
    }

    @Transactional(readOnly = true)
    public DocumentActivityPageResponse list(String ownerId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        var events = repository.findByOwnerIdOrderByOccurredAtAscIdAsc(
                ownerId, PageRequest.of(page, size));
        return new DocumentActivityPageResponse(
                events.stream().map(this::response).toList(),
                events.getNumber(),
                events.getSize(),
                events.getTotalElements(),
                events.getTotalPages());
    }

    private DocumentActivityEvent event(
            String eventKey,
            DocumentActivityType eventType,
            GeneratedDocument document,
            String result,
            LocalDateTime occurredAt) {
        return DocumentActivityEvent.builder()
                .eventKey(eventKey)
                .ownerId(document.getUserId())
                .eventType(eventType)
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .documentType(document.getDocumentType())
                .sourceType(document.getSourceType())
                .version(document.getVersion())
                .result(result)
                .occurredAt(occurredAt)
                .retentionExpiresAt(occurredAt.plusDays(
                        retentionProperties.getLifecycleAuditDays()))
                .build();
    }

    private DocumentActivityEventResponse response(DocumentActivityEvent event) {
        return new DocumentActivityEventResponse(
                event.getId(),
                event.getEventType(),
                event.getDocumentId(),
                event.getDocumentFamilyId(),
                event.getDocumentType(),
                event.getSourceType(),
                event.getVersion(),
                event.getResult(),
                event.getOccurredAt().atOffset(ZoneOffset.UTC));
    }
}

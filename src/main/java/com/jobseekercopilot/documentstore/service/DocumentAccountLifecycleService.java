package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.AccountDocumentDeletionResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.dto.DocumentLifecycleEventResponse;
import com.jobseekercopilot.documentstore.dto.DocumentPersonalDataExport;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleEvent;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentAccountLifecycleService {

    private final GeneratedDocumentRepository documentRepository;
    private final GeneratedDocumentService documentService;
    private final DocumentFileService fileService;
    private final DocumentLifecycleEventRepository eventRepository;
    private final DocumentRetentionService retentionService;

    @Transactional(readOnly = true)
    public DocumentPersonalDataExport export(String ownerId) {
        var documents = documentService.getDocumentsByUserId(ownerId);
        List<DocumentFileResponse> files = new ArrayList<>();
        documents.stream()
                .filter(document -> document.getRetentionState()
                        != DocumentRetentionState.DELETED)
                .filter(document -> document.getRetentionState()
                        != DocumentRetentionState.PURGED)
                .forEach(document -> files.addAll(
                        fileService.getFilesForDocument(ownerId, document.getId())));
        return new DocumentPersonalDataExport(
                "document-personal-data.v1",
                Instant.now(),
                List.copyOf(documents),
                List.copyOf(files),
                eventRepository.findByOwnerIdOrderByOccurredAtAscIdAsc(ownerId)
                        .stream()
                        .map(this::eventResponse)
                        .toList());
    }

    @Transactional
    public AccountDocumentDeletionResponse recoverablyDelete(
            String ownerId, String operationId) {
        var documents = documentRepository.findByUserId(ownerId).stream()
                .sorted(Comparator.comparing(document -> document.getId().toString()))
                .toList();
        int deleted = 0;
        int alreadyDeleted = 0;
        int held = 0;
        int purged = 0;
        for (var document : documents) {
            if (document.getRetentionState() == DocumentRetentionState.PURGED) {
                purged++;
            } else if (document.getRetentionState() == DocumentRetentionState.DELETED) {
                alreadyDeleted++;
            } else if (document.isLegalHold()) {
                held++;
            } else {
                retentionService.softDelete(
                        ownerId,
                        document.getId(),
                        "account-lifecycle:" + operationId);
                deleted++;
            }
        }
        return new AccountDocumentDeletionResponse(
                deleted, alreadyDeleted, held, purged);
    }

    private DocumentLifecycleEventResponse eventResponse(DocumentLifecycleEvent event) {
        return DocumentLifecycleEventResponse.builder()
                .id(event.getId())
                .documentId(event.getDocumentId())
                .action(event.getAction())
                .fromState(event.getFromState())
                .toState(event.getToState())
                .actorId(event.getActorId())
                .policyVersion(event.getPolicyVersion())
                .occurredAt(event.getOccurredAt().atOffset(ZoneOffset.UTC))
                .build();
    }
}

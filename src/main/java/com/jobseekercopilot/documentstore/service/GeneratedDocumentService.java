package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {

    private final GeneratedDocumentRepository repository;
    private final DocumentFileLifecycleService fileLifecycleService;
    private final DocumentOperationLock operationLock;
    private final DocumentStoreMetrics metrics;

    @Transactional
    public GeneratedDocumentResponse createDocument(
            String ownerId,
            CreateDocumentRequest request,
            String requestedOperationKey) {
        return metrics.observe(
                "document",
                "create",
                request.getDocumentType(),
                null,
                () -> createDocumentInternal(
                        ownerId, request, requestedOperationKey));
    }

    private GeneratedDocumentResponse createDocumentInternal(
            String ownerId,
            CreateDocumentRequest request,
            String requestedOperationKey) {
        String operationKey = IdempotencyKeys.validate(requestedOperationKey);
        String applicationId = blankToNull(request.getApplicationId());
        boolean active = request.getActive() == null || request.getActive();
        DocumentSourceType sourceType = request.getSourceType() == null
                ? DocumentSourceType.GENERATED
                : request.getSourceType();
        String fingerprint = OperationFingerprint.sha256(
                applicationId,
                request.getJobId(),
                request.getDocumentType(),
                request.getTitle(),
                OperationFingerprint.sha256(request.getContent()),
                request.getVersion(),
                active,
                blankToNull(request.getOriginalFilename()),
                sourceType,
                blankToNull(request.getCreatedBy()));

        if (operationKey != null) {
            operationLock.acquire(lockScope("document-idempotency", ownerId, operationKey));
            GeneratedDocument replay = repository
                    .findByUserIdAndOperationKey(ownerId, operationKey)
                    .orElse(null);
            if (replay != null) {
                requireMatchingFingerprint(replay.getRequestSha256(), fingerprint);
                return mapToResponse(replay);
            }
        }

        Integer version = request.getVersion();
        if (applicationId != null) {
            operationLock.acquire(lockScope(
                    "document-version",
                    ownerId,
                    applicationId,
                    request.getDocumentType()));
            int nextVersion = nextVersion(ownerId, applicationId, request.getDocumentType());
            if (version != null && version != nextVersion) {
                throw new OperationConflictException(
                        "Requested document version does not match the next available version.");
            }
            version = nextVersion;
            if (active) {
                deactivateCurrentVersions(
                        ownerId, applicationId, request.getDocumentType());
            }
        }

        GeneratedDocument document = GeneratedDocument.builder()
                .userId(ownerId)
                .jobId(request.getJobId())
                .applicationId(applicationId)
                .documentType(request.getDocumentType())
                .title(request.getTitle())
                .content(request.getContent())
                .version(version == null ? 1 : version)
                .active(active)
                .operationKey(operationKey)
                .requestSha256(operationKey == null ? null : fingerprint)
                .originalFilename(blankToNull(request.getOriginalFilename()))
                .sourceType(sourceType)
                .createdBy(blankToNull(request.getCreatedBy()))
                .build();

        GeneratedDocument saved = repository.saveAndFlush(document);
        metrics.recordPayload(
                "document",
                "stored",
                saved.getDocumentType(),
                null,
                textSize(saved.getContent()));
        return mapToResponse(saved);
    }

    public GeneratedDocumentResponse getDocumentById(String ownerId, UUID id) {
        return metrics.observe("document", "retrieve", null, null, () -> {
            GeneratedDocument document = findOwnedDocument(ownerId, id);
            metrics.recordPayload(
                    "document",
                    "retrieved",
                    document.getDocumentType(),
                    null,
                    textSize(document.getContent()));
            return mapToResponse(document);
        });
    }

    public List<GeneratedDocumentResponse> getDocumentsByUserId(String userId) {
        return metrics.observe("document", "list", null, null, () -> {
            List<GeneratedDocument> documents = repository.findByUserId(userId);
            recordRetrievedBatch(documents);
            return documents.stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        });
    }

    public List<GeneratedDocumentResponse> getDocumentsByUserIdAndJobId(String userId, String jobId) {
        return metrics.observe("document", "list", null, null, () -> {
            List<GeneratedDocument> documents =
                    repository.findByUserIdAndJobId(userId, jobId);
            recordRetrievedBatch(documents);
            return documents.stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        });
    }

    @Transactional
    public void deleteDocument(String ownerId, UUID id) {
        metrics.observe("document", "delete", null, null, () -> {
            deleteDocumentInternal(ownerId, id);
        });
    }

    private void deleteDocumentInternal(String ownerId, UUID id) {
        GeneratedDocument document = findOwnedDocument(ownerId, id);
        if (document.getApplicationId() != null) {
            operationLock.acquire(lockScope(
                    "document-version",
                    ownerId,
                    document.getApplicationId(),
                    document.getDocumentType()));
            document = findOwnedDocument(ownerId, id);
        }
        fileLifecycleService.deleteForDocuments(List.of(document.getId()));
        repository.delete(document);
    }

    @Transactional
    public GeneratedDocumentResponse activateDocumentVersion(
            String ownerId,
            String applicationId,
            DocumentType documentType,
            UUID documentId) {
        return metrics.observe(
                "document",
                "activate",
                documentType,
                null,
                () -> activateDocumentVersionInternal(
                        ownerId, applicationId, documentType, documentId));
    }

    private GeneratedDocumentResponse activateDocumentVersionInternal(
            String ownerId,
            String applicationId,
            DocumentType documentType,
            UUID documentId) {
        operationLock.acquire(lockScope(
                "document-version", ownerId, applicationId, documentType));
        GeneratedDocument document = findOwnedDocument(ownerId, documentId);
        if (applicationId == null || applicationId.isBlank() || !applicationId.equals(document.getApplicationId())) {
            throw ResourceNotFoundException.documentNotFound();
        }
        if (documentType == null || documentType != document.getDocumentType()) {
            throw ResourceNotFoundException.documentNotFound();
        }

        if (document.isActive()) {
            return mapToResponse(document);
        }
        deactivateCurrentVersions(ownerId, applicationId, documentType);
        document.setActive(true);
        repository.saveAndFlush(document);
        return mapToResponse(document);
    }

    @Transactional
    public void deactivateApplicationDocuments(String ownerId, String applicationId) {
        metrics.observe("document", "deactivate", null, null, () -> {
            deactivateApplicationDocumentsInternal(ownerId, applicationId);
        });
    }

    private void deactivateApplicationDocumentsInternal(
            String ownerId, String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return;
        }
        operationLock.acquire(lockScope(
                "document-version", ownerId, applicationId, DocumentType.CV));
        operationLock.acquire(lockScope(
                "document-version", ownerId, applicationId, DocumentType.COVER_LETTER));
        List<GeneratedDocument> documents =
                repository.findByApplicationIdAndUserId(applicationId, ownerId);
        documents.forEach(document -> document.setActive(false));
        repository.saveAllAndFlush(documents);
    }

    private Integer nextVersion(String ownerId, String applicationId, DocumentType documentType) {
        return repository.findFirstByApplicationIdAndDocumentTypeAndUserIdOrderByVersionDesc(
                        applicationId,
                        documentType,
                        ownerId)
                .map(GeneratedDocument::getVersion)
                .map(version -> version + 1)
                .orElse(1);
    }

    private void deactivateCurrentVersions(
            String ownerId,
            String applicationId,
            DocumentType documentType) {
        List<GeneratedDocument> activeVersions =
                repository.findByApplicationIdAndDocumentTypeAndActiveTrueAndUserId(
                        applicationId,
                        documentType,
                        ownerId);
        boolean repairedMultipleActive = activeVersions.size() > 1;
        activeVersions.forEach(activeVersion -> activeVersion.setActive(false));
        repository.saveAllAndFlush(activeVersions);
        if (repairedMultipleActive) {
            metrics.recordReconciliation(
                    "document", "repaired", "multiple_active");
        }
    }

    private void requireMatchingFingerprint(String stored, String requested) {
        if (!requested.equals(stored)) {
            throw new OperationConflictException(
                    "Idempotency-Key was already used for a different document operation.");
        }
    }

    private String lockScope(String prefix, Object... parts) {
        return prefix + ":" + OperationFingerprint.sha256(parts);
    }

    private GeneratedDocument findOwnedDocument(String ownerId, UUID id) {
        return repository.findByIdAndUserId(id, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
    }

    private GeneratedDocumentResponse mapToResponse(GeneratedDocument document) {
        return GeneratedDocumentResponse.builder()
                .id(document.getId())
                .userId(document.getUserId())
                .jobId(document.getJobId())
                .applicationId(document.getApplicationId())
                .documentType(document.getDocumentType())
                .title(document.getTitle())
                .content(document.getContent())
                .version(document.getVersion())
                .active(document.isActive())
                .originalFilename(document.getOriginalFilename())
                .sourceType(document.getSourceType())
                .createdBy(document.getCreatedBy())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void recordRetrievedBatch(List<GeneratedDocument> documents) {
        long bytes = documents.stream()
                .mapToLong(document -> textSize(document.getContent()))
                .sum();
        metrics.recordPayload("document", "retrieved", null, null, bytes);
    }

    private long textSize(String content) {
        return content == null
                ? 0
                : content.getBytes(StandardCharsets.UTF_8).length;
    }
}

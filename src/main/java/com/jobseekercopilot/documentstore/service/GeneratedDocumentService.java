package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedDocumentService.class);

    private final GeneratedDocumentRepository repository;
    private final DocumentFileLifecycleService fileLifecycleService;
    private final DocumentOperationLock operationLock;

    @Transactional
    public GeneratedDocumentResponse createDocument(
            String ownerId,
            CreateDocumentRequest request,
            String requestedOperationKey) {
        long startedAt = System.nanoTime();
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
                log.info("Generated document idempotent retry replayed documentType={} version={}",
                        replay.getDocumentType(), replay.getVersion());
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
        log.info("Generated document metadata saved documentType={} titlePresent={} durationMs={}",
                saved.getDocumentType(),
                saved.getTitle() != null && !saved.getTitle().isBlank(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
    }

    public GeneratedDocumentResponse getDocumentById(String ownerId, UUID id) {
        long startedAt = System.nanoTime();
        GeneratedDocument document = findOwnedDocument(ownerId, id);
        log.info("Generated document metadata loaded documentType={} durationMs={}",
                document.getDocumentType(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(document);
    }

    public List<GeneratedDocumentResponse> getDocumentsByUserId(String userId) {
        return repository.findByUserId(userId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<GeneratedDocumentResponse> getDocumentsByUserIdAndJobId(String userId, String jobId) {
        return repository.findByUserIdAndJobId(userId, jobId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteDocument(String ownerId, UUID id) {
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
        log.info("Generated document deleted");
    }

    @Transactional
    public GeneratedDocumentResponse activateDocumentVersion(
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
        log.info("Document version activated documentType={} version={}",
                documentType,
                document.getVersion());
        return mapToResponse(document);
    }

    @Transactional
    public void deactivateApplicationDocuments(String ownerId, String applicationId) {
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
        log.info("Application documents deactivated count={}", documents.size());
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
        activeVersions.forEach(activeVersion -> activeVersion.setActive(false));
        repository.saveAllAndFlush(activeVersions);
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
}

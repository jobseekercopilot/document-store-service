package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.DocumentArtifactAvailability;
import com.jobseekercopilot.documentstore.dto.DocumentArtifactManifestItem;
import com.jobseekercopilot.documentstore.dto.DocumentArtifactRole;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFamilySummary;
import com.jobseekercopilot.documentstore.dto.DocumentVersionHistoryItem;
import com.jobseekercopilot.documentstore.dto.DocumentTombstoneAssociationResponse;
import com.jobseekercopilot.documentstore.dto.ExpectedCurrentState;
import com.jobseekercopilot.documentstore.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentstore.entity.DocumentActivityType;
import com.jobseekercopilot.documentstore.entity.DocumentCurrentCommand;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.DocumentCurrentCommandRepository;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.repository.DocumentTombstoneAssociationRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentFamilyHistoryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentCurrentCommandRepository currentCommandRepository;
    private final DocumentTombstoneAssociationRepository
            tombstoneAssociationRepository;
    private final DocumentOperationLock operationLock;
    private final DocumentOwnerErasureGuard ownerErasureGuard;
    private final DocumentStoreMetrics metrics;
    private final DocumentActivityService activityService;

    @Transactional(readOnly = true)
    public DocumentFamilyPageResponse listFamilies(String ownerId, int page, int size) {
        return metrics.observe("document", "list", null, null, () -> {
            if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "Page must be non-negative and size must be between 1 and 100.");
            }
            Page<GeneratedDocument> latestPage = documentRepository
                    .findLatestFamilyVersions(ownerId, PageRequest.of(page, size));
            List<DocumentFamilySummary> items = latestPage.getContent().stream()
                    .map(latest -> summary(ownerId, latest))
                    .toList();
            return new DocumentFamilyPageResponse(
                    items,
                    latestPage.getNumber(),
                    latestPage.getSize(),
                    latestPage.getTotalElements(),
                    latestPage.getTotalPages());
        });
    }

    @Transactional(readOnly = true)
    public DocumentFamilyHistoryResponse getFamilyHistory(
            String ownerId, UUID documentFamilyId) {
        return metrics.observe("document", "retrieve", null, null, () -> {
            List<GeneratedDocument> versions = documentRepository
                    .findByDocumentFamilyIdAndUserIdOrderByVersionDesc(
                            documentFamilyId, ownerId);
            if (versions.isEmpty()) {
                throw ResourceNotFoundException.documentNotFound();
            }
            List<UUID> documentIds = versions.stream()
                    .map(GeneratedDocument::getId)
                    .toList();
            Map<UUID, List<ExportedDocumentFile>> artifactsByDocument = fileRepository
                    .findByGeneratedDocumentIdInAndOwnerIdOrderByCreatedAtAsc(
                            documentIds, ownerId)
                    .stream()
                    .collect(Collectors.groupingBy(
                            ExportedDocumentFile::getGeneratedDocumentId));
            GeneratedDocument newest = versions.get(0);
            GeneratedDocument current = versions.stream()
                    .filter(GeneratedDocument::isActive)
                    .findFirst()
                    .orElse(null);
            List<DocumentVersionHistoryItem> history = versions.stream()
                    .map(version -> versionHistory(
                            version,
                            artifactsByDocument.getOrDefault(
                                    version.getId(), List.of())))
                    .toList();
            return new DocumentFamilyHistoryResponse(
                    documentFamilyId,
                    newest.getJobId(),
                    newest.getDocumentType(),
                    current == null ? null : current.getId(),
                    current == null ? null : current.getVersion(),
                    history);
        });
    }

    @Transactional
    public DocumentFamilyCurrentResponse selectCurrent(
            String ownerId,
            UUID documentFamilyId,
            SelectFamilyCurrentRequest request,
            String requestedIdempotencyKey) {
        ownerErasureGuard.requireWritable(ownerId);
        return metrics.observe("document", "activate", null, null, () ->
                selectCurrentInternal(
                        ownerId,
                        documentFamilyId,
                        request,
                        requestedIdempotencyKey));
    }

    private DocumentFamilyCurrentResponse selectCurrentInternal(
            String ownerId,
            UUID documentFamilyId,
            SelectFamilyCurrentRequest request,
            String requestedIdempotencyKey) {
        String idempotencyKey = IdempotencyKeys.validate(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("Idempotency-Key is required.");
        }
        validateExpectation(request);
        String fingerprint = OperationFingerprint.sha256(
                documentFamilyId,
                request.documentId(),
                request.expectedCurrentState(),
                request.expectedCurrentDocumentId());

        operationLock.acquire(lockScope(
                "document-current-idempotency", ownerId, idempotencyKey));
        DocumentCurrentCommand replay = currentCommandRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.getRequestSha256())) {
                throw new OperationConflictException(
                        "Idempotency-Key was already used for a different current-selection command.");
            }
            return commandResponse(replay);
        }

        GeneratedDocument requested = documentRepository
                .findByIdAndUserId(request.documentId(), ownerId)
                .filter(document -> documentFamilyId.equals(
                        document.getDocumentFamilyId()))
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        operationLock.acquire(lockScope("document-family", ownerId, documentFamilyId));
        requested = documentRepository.findByIdAndUserId(request.documentId(), ownerId)
                .filter(document -> documentFamilyId.equals(
                        document.getDocumentFamilyId()))
                .orElseThrow(ResourceNotFoundException::documentNotFound);

        List<GeneratedDocument> currentVersions = documentRepository
                .findByDocumentFamilyIdAndActiveTrueAndUserId(
                        documentFamilyId, ownerId);
        if (currentVersions.size() > 1) {
            throw new OperationConflictException(
                    "Document family has an inconsistent current pointer.");
        }
        GeneratedDocument actualCurrent = currentVersions.stream()
                .findFirst()
                .orElse(null);
        requireExpectedCurrent(request, actualCurrent);
        requireEligible(requested);

        boolean changed = actualCurrent == null
                || !actualCurrent.getId().equals(requested.getId());
        if (changed) {
            currentVersions.forEach(current -> current.setActive(false));
            documentRepository.saveAllAndFlush(currentVersions);
            requested.setActive(true);
            requested = documentRepository.saveAndFlush(requested);
            activityService.recordOnce(
                    "current:" + OperationFingerprint.sha256(
                            ownerId, idempotencyKey),
                    DocumentActivityType.DOCUMENT_CURRENT_VERSION_CHANGED,
                    requested,
                    actualCurrent == null
                            ? "FIRST_CURRENT_SELECTED"
                            : "CURRENT_CHANGED",
                    LocalDateTime.now());
        }

        DocumentCurrentCommand command = currentCommandRepository.saveAndFlush(
                DocumentCurrentCommand.builder()
                        .ownerId(ownerId)
                        .idempotencyKey(idempotencyKey)
                        .requestSha256(fingerprint)
                        .documentFamilyId(documentFamilyId)
                        .currentDocumentId(requested.getId())
                        .currentVersion(requested.getVersion())
                        .build());
        return commandResponse(command);
    }

    private DocumentFamilySummary summary(String ownerId, GeneratedDocument latest) {
        List<GeneratedDocument> versions = documentRepository
                .findByDocumentFamilyIdAndUserIdOrderByVersionDesc(
                        latest.getDocumentFamilyId(), ownerId);
        GeneratedDocument current = versions.stream()
                .filter(GeneratedDocument::isActive)
                .findFirst()
                .orElse(null);
        GeneratedDocument oldest = versions.get(versions.size() - 1);
        return new DocumentFamilySummary(
                latest.getDocumentFamilyId(),
                latest.getJobId(),
                latest.getDocumentType(),
                latest.getId(),
                latest.getVersion(),
                latest.getSourceType(),
                latest.getLifecycleState(),
                latest.getRetentionState(),
                current == null ? null : current.getId(),
                current == null ? null : current.getVersion(),
                versions.size(),
                utc(oldest.getCreatedAt()),
                utc(latest.getUpdatedAt()));
    }

    private DocumentVersionHistoryItem versionHistory(
            GeneratedDocument version,
            List<ExportedDocumentFile> artifacts) {
        return new DocumentVersionHistoryItem(
                version.getId(),
                version.getVersion(),
                version.getTitle(),
                version.getSourceType(),
                version.getLifecycleState(),
                version.getRetentionState(),
                version.isActive(),
                utc(version.getApprovedAt()),
                utc(version.getArchivedAt()),
                utc(version.getDeletedAt()),
                utc(version.getPurgeEligibleAt()),
                utc(version.getPurgedAt()),
                version.getUnavailableReason(),
                utc(version.getCreatedAt()),
                utc(version.getUpdatedAt()),
                tombstoneAssociationRepository
                        .findByDocumentIdOrderByApplicationIdAsc(
                                version.getId())
                        .stream()
                        .map(association ->
                                new DocumentTombstoneAssociationResponse(
                                        association.getApplicationId(),
                                        association.getDocumentType(),
                                        association.getAssociationState(),
                                        association.getApplicationStatus(),
                                        utc(association.getFrozenAt())))
                        .toList(),
                artifacts.stream().map(this::artifact).toList());
    }

    private DocumentArtifactManifestItem artifact(ExportedDocumentFile file) {
        return new DocumentArtifactManifestItem(
                file.getId(),
                file.getSource() == FileSource.USER_UPLOADED
                        ? DocumentArtifactRole.ORIGINAL
                        : DocumentArtifactRole.DERIVED,
                file.getFileType(),
                file.getSource(),
                file.getStorageStatus() == ObjectStorageStatus.AVAILABLE
                        ? DocumentArtifactAvailability.AVAILABLE
                        : DocumentArtifactAvailability.UNAVAILABLE,
                file.getContentSize(),
                utc(file.getStoredAt()),
                utc(file.getCreatedAt()),
                utc(file.getUpdatedAt()));
    }

    private void validateExpectation(SelectFamilyCurrentRequest request) {
        boolean selected = request.expectedCurrentState() == ExpectedCurrentState.SELECTED;
        if (selected != (request.expectedCurrentDocumentId() != null)) {
            throw new IllegalArgumentException(
                    "expectedCurrentDocumentId must be present only when expectedCurrentState is SELECTED.");
        }
    }

    private void requireExpectedCurrent(
            SelectFamilyCurrentRequest request,
            GeneratedDocument actualCurrent) {
        if (request.expectedCurrentState() == ExpectedCurrentState.NONE) {
            if (actualCurrent != null) {
                throw staleCurrent();
            }
            return;
        }
        if (actualCurrent == null
                || !actualCurrent.getId().equals(
                        request.expectedCurrentDocumentId())) {
            throw staleCurrent();
        }
    }

    private void requireEligible(GeneratedDocument document) {
        if (document.getLifecycleState() != DocumentLifecycleState.APPROVED
                || document.getRetentionState() != DocumentRetentionState.AVAILABLE) {
            throw new OperationConflictException(
                    "Only an approved and available document version can be selected as current.");
        }
    }

    private OperationConflictException staleCurrent() {
        return new OperationConflictException(
                "Document family current pointer changed; refresh the family history and retry.");
    }

    private DocumentFamilyCurrentResponse commandResponse(
            DocumentCurrentCommand command) {
        return new DocumentFamilyCurrentResponse(
                command.getId(),
                command.getDocumentFamilyId(),
                command.getCurrentDocumentId(),
                command.getCurrentVersion(),
                utc(command.getCreatedAt()));
    }

    private String lockScope(String prefix, Object... parts) {
        return prefix + ":" + OperationFingerprint.sha256(parts);
    }

    private OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

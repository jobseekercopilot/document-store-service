package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentReferenceResponse;
import com.jobseekercopilot.documentstore.dto.GenerationMetadata;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.GenerationProvenance;
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
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedDocumentService.class);

    private final GeneratedDocumentRepository repository;
    private final DocumentRetentionService retentionService;
    private final DocumentOperationLock operationLock;

    @Transactional
    public GeneratedDocumentResponse createDocument(
            String ownerId,
            CreateDocumentRequest request,
            String requestedOperationKey) {
        long startedAt = System.nanoTime();
        String operationKey = IdempotencyKeys.validate(requestedOperationKey);
        String applicationId = blankToNull(request.getApplicationId());
        if (Boolean.TRUE.equals(request.getActive())) {
            throw new IllegalArgumentException(
                    "Documents are created as drafts and must be explicitly approved.");
        }
        DocumentSourceType sourceType = request.getSourceType() == null
                ? DocumentSourceType.GENERATED
                : request.getSourceType();
        validateGenerationMetadata(sourceType, request.getGenerationMetadata());
        String fingerprint = OperationFingerprint.sha256(
                applicationId,
                request.getDocumentFamilyId(),
                request.getJobId(),
                request.getDocumentType(),
                request.getTitle(),
                OperationFingerprint.sha256(request.getContent()),
                request.getVersion(),
                blankToNull(request.getOriginalFilename()),
                sourceType,
                generationMetadataFingerprint(request.getGenerationMetadata()),
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

        UUID documentFamilyId = request.getDocumentFamilyId() == null
                ? UUID.randomUUID()
                : request.getDocumentFamilyId();
        operationLock.acquire(lockScope("document-family", ownerId, documentFamilyId));
        GeneratedDocument latestVersion = repository
                .findFirstByDocumentFamilyIdAndUserIdOrderByVersionDesc(
                        documentFamilyId, ownerId)
                .orElse(null);
        int nextVersion = latestVersion == null ? 1 : latestVersion.getVersion() + 1;
        validateFamily(request, latestVersion);
        if (request.getVersion() != null && request.getVersion() != nextVersion) {
            throw new OperationConflictException(
                    "Requested document version does not match the next available version.");
        }

        GeneratedDocument document = GeneratedDocument.builder()
                .userId(ownerId)
                .jobId(request.getJobId())
                .applicationId(applicationId)
                .documentFamilyId(documentFamilyId)
                .documentType(request.getDocumentType())
                .title(request.getTitle())
                .content(request.getContent())
                .contentSha256(contentSha256(request.getContent()))
                .version(nextVersion)
                .active(false)
                .lifecycleState(DocumentLifecycleState.DRAFT)
                .generationProvenance(toEntity(request.getGenerationMetadata()))
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

    public DocumentReferenceResponse getDocumentReference(String ownerId, UUID id) {
        GeneratedDocument document = findOwnedDocument(ownerId, id);
        if (document.getLifecycleState() != DocumentLifecycleState.APPROVED) {
            throw new OperationConflictException(
                    "Document version is not approved for application use.");
        }
        if (document.getRetentionState() != DocumentRetentionState.AVAILABLE) {
            throw new OperationConflictException(
                    "Document version is not available for application use.");
        }
        return mapToReference(document);
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

    public void deleteDocument(String ownerId, UUID id, String actorId) {
        retentionService.softDelete(ownerId, id, actorId);
    }

    @Transactional
    public GeneratedDocumentResponse activateDocumentVersion(
            String ownerId,
            String applicationId,
            DocumentType documentType,
            UUID documentId) {
        GeneratedDocument document = findOwnedDocument(ownerId, documentId);
        if (applicationId == null || applicationId.isBlank() || !applicationId.equals(document.getApplicationId())) {
            throw ResourceNotFoundException.documentNotFound();
        }
        if (documentType == null || documentType != document.getDocumentType()) {
            throw ResourceNotFoundException.documentNotFound();
        }
        return selectCurrentVersion(ownerId, document);
    }

    @Transactional
    public GeneratedDocumentResponse approveDocumentVersion(String ownerId, UUID documentId) {
        GeneratedDocument document = findOwnedDocument(ownerId, documentId);
        operationLock.acquire(lockScope(
                "document-family", ownerId, document.getDocumentFamilyId()));
        document = findOwnedDocument(ownerId, documentId);
        if (document.getLifecycleState() == DocumentLifecycleState.APPROVED) {
            requireAvailable(document);
            return mapToResponse(document);
        }
        requireAvailable(document);
        if (document.getSourceType() == DocumentSourceType.GENERATED
                && document.getGenerationProvenance() == null) {
            throw new OperationConflictException(
                    "Generated document provenance is required before approval.");
        }
        deactivateCurrentVersions(ownerId, document.getDocumentFamilyId());
        document.setLifecycleState(DocumentLifecycleState.APPROVED);
        document.setActive(true);
        document.setApprovedAt(LocalDateTime.now());
        document.setApprovedBy(ownerId);
        if (document.getContentSha256() == null) {
            document.setContentSha256(contentSha256(document.getContent()));
        }
        repository.saveAndFlush(document);
        log.info("Document version approved documentType={} version={}",
                document.getDocumentType(), document.getVersion());
        return mapToResponse(document);
    }

    @Transactional
    public GeneratedDocumentResponse selectCurrentDocumentVersion(
            String ownerId, UUID documentId) {
        GeneratedDocument document = findOwnedDocument(ownerId, documentId);
        return selectCurrentVersion(ownerId, document);
    }

    @Transactional
    public void deactivateApplicationDocuments(String ownerId, String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return;
        }
        log.info("Deprecated application document deactivation ignored; current selection is application independent");
    }

    private void deactivateCurrentVersions(
            String ownerId,
            UUID documentFamilyId) {
        List<GeneratedDocument> activeVersions =
                repository.findByDocumentFamilyIdAndActiveTrueAndUserId(
                        documentFamilyId,
                        ownerId);
        activeVersions.forEach(activeVersion -> activeVersion.setActive(false));
        repository.saveAllAndFlush(activeVersions);
    }

    private GeneratedDocumentResponse selectCurrentVersion(
            String ownerId, GeneratedDocument initialDocument) {
        operationLock.acquire(lockScope(
                "document-family", ownerId, initialDocument.getDocumentFamilyId()));
        GeneratedDocument document = findOwnedDocument(ownerId, initialDocument.getId());
        if (document.getLifecycleState() != DocumentLifecycleState.APPROVED) {
            throw new OperationConflictException(
                    "Only an approved document version can be selected as current.");
        }
        requireAvailable(document);
        if (document.isActive()) {
            return mapToResponse(document);
        }
        deactivateCurrentVersions(ownerId, document.getDocumentFamilyId());
        document.setActive(true);
        repository.saveAndFlush(document);
        log.info("Document version selected as current documentType={} version={}",
                document.getDocumentType(), document.getVersion());
        return mapToResponse(document);
    }

    private void validateFamily(
            CreateDocumentRequest request, GeneratedDocument latestVersion) {
        if (latestVersion == null) {
            return;
        }
        if (!latestVersion.getJobId().equals(request.getJobId())
                || latestVersion.getDocumentType() != request.getDocumentType()) {
            throw ResourceNotFoundException.documentNotFound();
        }
        if (latestVersion.getRetentionState() != DocumentRetentionState.AVAILABLE) {
            throw new OperationConflictException(
                    "Archived or deleted document families must be restored before adding a version.");
        }
    }

    private void validateGenerationMetadata(
            DocumentSourceType sourceType, GenerationMetadata metadata) {
        if (sourceType == DocumentSourceType.UPLOADED && metadata != null) {
            throw new IllegalArgumentException(
                    "Uploaded documents cannot include AI generation metadata.");
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
                .documentFamilyId(document.getDocumentFamilyId())
                .documentType(document.getDocumentType())
                .title(document.getTitle())
                .content(document.getContent())
                .version(document.getVersion())
                .active(document.isActive())
                .current(document.isActive())
                .lifecycleState(document.getLifecycleState())
                .retentionState(document.getRetentionState())
                .contentSha256(document.getContentSha256())
                .generationMetadata(toDto(document.getGenerationProvenance()))
                .approvedAt(document.getApprovedAt())
                .approvedBy(document.getApprovedBy())
                .archivedAt(document.getArchivedAt())
                .deletedAt(document.getDeletedAt())
                .purgeEligibleAt(document.getPurgeEligibleAt())
                .legalHold(document.isLegalHold())
                .originalFilename(document.getOriginalFilename())
                .sourceType(document.getSourceType())
                .createdBy(document.getCreatedBy())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private void requireAvailable(GeneratedDocument document) {
        if (document.getRetentionState() != DocumentRetentionState.AVAILABLE) {
            throw new OperationConflictException(
                    "Archived or deleted documents cannot become current or approved.");
        }
    }

    private DocumentReferenceResponse mapToReference(GeneratedDocument document) {
        return DocumentReferenceResponse.builder()
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .jobId(document.getJobId())
                .applicationId(document.getApplicationId())
                .documentType(document.getDocumentType())
                .version(document.getVersion())
                .contentSha256(document.getContentSha256())
                .lifecycleState(document.getLifecycleState())
                .current(document.isActive())
                .generationMetadata(toDto(document.getGenerationProvenance()))
                .build();
    }

    private GenerationProvenance toEntity(GenerationMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return GenerationProvenance.builder()
                .releaseId(metadata.getReleaseId())
                .bundleId(metadata.getBundleId())
                .bundleVersion(metadata.getBundleVersion())
                .bundleSha256(metadata.getBundleSha256())
                .templateVersion(metadata.getTemplateVersion())
                .templateSha256(metadata.getTemplateSha256())
                .rulesVersion(metadata.getRulesVersion())
                .rulesSha256(metadata.getRulesSha256())
                .schemaId(metadata.getSchemaId())
                .schemaVersion(metadata.getSchemaVersion())
                .schemaSha256(metadata.getSchemaSha256())
                .evaluationPolicyVersion(metadata.getEvaluationPolicyVersion())
                .evaluationPolicySha256(metadata.getEvaluationPolicySha256())
                .build();
    }

    private GenerationMetadata toDto(GenerationProvenance provenance) {
        if (provenance == null) {
            return null;
        }
        return GenerationMetadata.builder()
                .releaseId(provenance.getReleaseId())
                .bundleId(provenance.getBundleId())
                .bundleVersion(provenance.getBundleVersion())
                .bundleSha256(provenance.getBundleSha256())
                .templateVersion(provenance.getTemplateVersion())
                .templateSha256(provenance.getTemplateSha256())
                .rulesVersion(provenance.getRulesVersion())
                .rulesSha256(provenance.getRulesSha256())
                .schemaId(provenance.getSchemaId())
                .schemaVersion(provenance.getSchemaVersion())
                .schemaSha256(provenance.getSchemaSha256())
                .evaluationPolicyVersion(provenance.getEvaluationPolicyVersion())
                .evaluationPolicySha256(provenance.getEvaluationPolicySha256())
                .build();
    }

    private String generationMetadataFingerprint(GenerationMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return OperationFingerprint.sha256(
                metadata.getReleaseId(),
                metadata.getBundleId(),
                metadata.getBundleVersion(),
                metadata.getBundleSha256(),
                metadata.getTemplateVersion(),
                metadata.getTemplateSha256(),
                metadata.getRulesVersion(),
                metadata.getRulesSha256(),
                metadata.getSchemaId(),
                metadata.getSchemaVersion(),
                metadata.getSchemaSha256(),
                metadata.getEvaluationPolicyVersion(),
                metadata.getEvaluationPolicySha256());
    }

    private String contentSha256(String content) {
        return OperationFingerprint.contentSha256(
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

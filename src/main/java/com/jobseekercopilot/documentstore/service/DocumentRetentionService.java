package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import com.jobseekercopilot.documentstore.dto.DocumentLifecycleEventResponse;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.dto.ApplicationWithdrawalCleanupRequest;
import com.jobseekercopilot.documentstore.dto.GenerationMetadata;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleAction;
import com.jobseekercopilot.documentstore.entity.DocumentApplicationWorkflowCommand;
import com.jobseekercopilot.documentstore.entity.DocumentLifecycleEvent;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.GenerationProvenance;
import com.jobseekercopilot.documentstore.entity.StorageOperationState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.DocumentLifecycleEventRepository;
import com.jobseekercopilot.documentstore.repository.DocumentApplicationWorkflowCommandRepository;
import com.jobseekercopilot.documentstore.repository.DocumentStorageOperationRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetentionService {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentRetentionService.class);

    private final GeneratedDocumentRepository documentRepository;
    private final DocumentLifecycleEventRepository eventRepository;
    private final DocumentApplicationWorkflowCommandRepository
            applicationWorkflowCommandRepository;
    private final DocumentStorageOperationRepository storageOperationRepository;
    private final DocumentFileLifecycleService fileLifecycleService;
    private final DocumentOperationLock operationLock;
    private final DocumentRetentionProperties properties;

    @Transactional
    public GeneratedDocumentResponse archive(String ownerId, UUID documentId, String actorId) {
        GeneratedDocument document = lockOwnedDocument(ownerId, documentId);
        if (document.getRetentionState() == DocumentRetentionState.ARCHIVED) {
            return response(document);
        }
        if (document.getRetentionState() == DocumentRetentionState.DELETED) {
            throw new OperationConflictException(
                    "Deleted documents must be restored before they can be archived.");
        }

        DocumentRetentionState previous = document.getRetentionState();
        LocalDateTime now = LocalDateTime.now();
        document.setActive(false);
        document.setRetentionState(DocumentRetentionState.ARCHIVED);
        document.setArchivedAt(now);
        document.setArchivedBy(actor(actorId));
        clearDeletion(document);
        documentRepository.saveAndFlush(document);
        record(document, DocumentLifecycleAction.ARCHIVED, previous, actorId, now);
        log.info("Document archived documentType={} version={}",
                document.getDocumentType(), document.getVersion());
        return response(document);
    }

    @Transactional
    public GeneratedDocumentResponse restore(String ownerId, UUID documentId, String actorId) {
        GeneratedDocument document = lockOwnedDocument(ownerId, documentId);
        if (document.getRetentionState() == DocumentRetentionState.AVAILABLE) {
            return response(document);
        }

        DocumentRetentionState previous = document.getRetentionState();
        LocalDateTime now = LocalDateTime.now();
        document.setActive(false);
        document.setRetentionState(DocumentRetentionState.AVAILABLE);
        document.setArchivedAt(null);
        document.setArchivedBy(null);
        clearDeletion(document);
        documentRepository.saveAndFlush(document);
        record(document, DocumentLifecycleAction.RESTORED, previous, actorId, now);
        log.info("Document restored documentType={} version={}",
                document.getDocumentType(), document.getVersion());
        return response(document);
    }

    @Transactional
    public void softDelete(String ownerId, UUID documentId, String actorId) {
        softDeleteInternal(ownerId, documentId, actorId, null);
    }

    @Transactional
    public void softDeleteForApplicationWithdrawal(
            String ownerId,
            ApplicationWithdrawalCleanupRequest request,
            String actorId) {
        List<UUID> documentIds = request.getDocumentIds().stream()
                .sorted()
                .toList();
        if (new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new IllegalArgumentException(
                    "Application withdrawal document IDs must be distinct.");
        }
        operationLock.acquire(
                "application-withdrawal:" + OperationFingerprint.sha256(
                        ownerId,
                        request.getApplicationId(),
                        request.getOperationId()));
        String requestSha256 = OperationFingerprint.sha256(
                ownerId,
                request.getApplicationId(),
                documentIds);
        DocumentApplicationWorkflowCommand existing =
                applicationWorkflowCommandRepository
                        .findByOperationIdAndOwnerId(
                                request.getOperationId(), ownerId)
                        .orElse(null);
        if (existing != null) {
            if (!existing.getApplicationId()
                            .equals(request.getApplicationId())
                    || !existing.getRequestSha256()
                            .equals(requestSha256)) {
                throw new OperationConflictException(
                        "Application withdrawal operation was reused with a different request.");
            }
            return;
        }
        String operationReference = request.getOperationId().toString();
        for (UUID documentId : documentIds) {
            softDeleteInternal(
                    ownerId,
                    documentId,
                    actorId,
                    operationReference);
        }
        applicationWorkflowCommandRepository.saveAndFlush(
                DocumentApplicationWorkflowCommand.builder()
                        .operationId(request.getOperationId())
                        .ownerId(ownerId)
                        .applicationId(request.getApplicationId())
                        .commandType("GENERATED_WITHDRAWAL")
                        .requestSha256(requestSha256)
                        .status("COMPLETED")
                        .build());
        log.info(
                "Application withdrawal document cleanup completed documentCount={}",
                documentIds.size());
    }

    private void softDeleteInternal(
            String ownerId,
            UUID documentId,
            String actorId,
            String operationReference) {
        GeneratedDocument document = lockOwnedDocument(ownerId, documentId);
        if (document.getRetentionState() == DocumentRetentionState.DELETED) {
            return;
        }
        if (document.isLegalHold()) {
            throw new OperationConflictException(
                    "Document is protected by a legal hold and cannot be deleted.");
        }

        DocumentRetentionState previous = document.getRetentionState();
        LocalDateTime now = LocalDateTime.now();
        document.setActive(false);
        document.setRetentionState(DocumentRetentionState.DELETED);
        document.setDeletedAt(now);
        document.setDeletedBy(actor(actorId));
        document.setPurgeEligibleAt(now.plusDays(properties.getRecoveryDays()));
        documentRepository.saveAndFlush(document);
        record(
                document,
                DocumentLifecycleAction.SOFT_DELETED,
                previous,
                actorId,
                now,
                operationReference);
        log.info("Document soft deleted documentType={} version={}",
                document.getDocumentType(), document.getVersion());
    }

    @Transactional
    public GeneratedDocumentResponse setLegalHold(
            String ownerId,
            UUID documentId,
            boolean active,
            String reference,
            String actorId) {
        GeneratedDocument document = lockOwnedDocument(ownerId, documentId);
        String normalizedReference = reference == null ? "" : reference.trim();
        if (normalizedReference.isEmpty()) {
            throw new IllegalArgumentException("Legal hold reference is required.");
        }
        if (document.isLegalHold() == active
                && (!active || normalizedReference.equals(document.getLegalHoldReference()))) {
            return response(document);
        }

        LocalDateTime now = LocalDateTime.now();
        if (active) {
            document.setLegalHold(true);
            document.setLegalHoldReference(normalizedReference);
            document.setLegalHoldUpdatedAt(now);
            document.setLegalHoldUpdatedBy(actor(actorId));
        } else {
            document.setLegalHold(false);
            document.setLegalHoldReference(null);
            document.setLegalHoldUpdatedAt(null);
            document.setLegalHoldUpdatedBy(null);
        }
        documentRepository.saveAndFlush(document);
        record(
                document,
                active
                        ? DocumentLifecycleAction.LEGAL_HOLD_APPLIED
                        : DocumentLifecycleAction.LEGAL_HOLD_RELEASED,
                document.getRetentionState(),
                actorId,
                now,
                normalizedReference);
        log.info("Document legal hold changed active={} documentType={} version={}",
                active, document.getDocumentType(), document.getVersion());
        return response(document);
    }

    @Transactional
    public void purge(String ownerId, UUID documentId, String actorId) {
        try {
            properties.requireApprovedPurgePolicy();
        } catch (IllegalStateException exception) {
            throw new OperationConflictException(exception.getMessage());
        }
        if (documentRepository.findByIdAndUserId(documentId, ownerId).isEmpty()) {
            if (eventRepository.existsByDocumentIdAndOwnerIdAndAction(
                    documentId, ownerId, DocumentLifecycleAction.PURGED)) {
                return;
            }
            throw ResourceNotFoundException.documentNotFound();
        }
        GeneratedDocument document = lockOwnedDocument(ownerId, documentId);
        if (document.getRetentionState() != DocumentRetentionState.DELETED) {
            throw new OperationConflictException(
                    "Only a soft-deleted document can be irreversibly purged.");
        }
        if (document.isLegalHold()) {
            throw new OperationConflictException(
                    "Document is protected by a legal hold and cannot be purged.");
        }
        if (document.getApplicationId() != null && !document.getApplicationId().isBlank()) {
            throw new OperationConflictException(
                    "Application-linked document history cannot be purged.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (document.getPurgeEligibleAt() == null
                || now.isBefore(document.getPurgeEligibleAt())) {
            throw new OperationConflictException(
                    "Document recovery window has not expired.");
        }
        if (storageOperationRepository.existsByGeneratedDocumentIdAndState(
                documentId, StorageOperationState.PREPARED)) {
            throw new OperationConflictException(
                    "Document has an unresolved storage operation and cannot be purged.");
        }

        fileLifecycleService.deleteForDocuments(List.of(documentId));
        record(
                document,
                DocumentLifecycleAction.PURGED,
                DocumentRetentionState.DELETED,
                actorId,
                now);
        documentRepository.delete(document);
        documentRepository.flush();
        log.info("Document irreversibly purged documentType={} version={}",
                document.getDocumentType(), document.getVersion());
    }

    @Transactional(readOnly = true)
    public List<DocumentLifecycleEventResponse> events(String ownerId, UUID documentId) {
        documentRepository.findByIdAndUserId(documentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        return eventRepository.findByDocumentIdAndOwnerIdOrderByOccurredAtAsc(
                        documentId, ownerId)
                .stream()
                .map(event -> DocumentLifecycleEventResponse.builder()
                        .id(event.getId())
                        .documentId(event.getDocumentId())
                        .action(event.getAction())
                        .fromState(event.getFromState())
                        .toState(event.getToState())
                        .actorId(event.getActorId())
                        .policyVersion(event.getPolicyVersion())
                        .occurredAt(event.getOccurredAt())
                        .build())
                .toList();
    }

    private GeneratedDocument lockOwnedDocument(String ownerId, UUID documentId) {
        GeneratedDocument initial = documentRepository.findByIdAndUserId(documentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
        operationLock.acquire(
                "document-family:" + OperationFingerprint.sha256(
                        ownerId, initial.getDocumentFamilyId()));
        return documentRepository.findByIdAndUserId(documentId, ownerId)
                .orElseThrow(ResourceNotFoundException::documentNotFound);
    }

    private void clearDeletion(GeneratedDocument document) {
        document.setDeletedAt(null);
        document.setDeletedBy(null);
        document.setPurgeEligibleAt(null);
    }

    private void record(
            GeneratedDocument document,
            DocumentLifecycleAction action,
            DocumentRetentionState fromState,
            String actorId,
            LocalDateTime occurredAt) {
        record(document, action, fromState, actorId, occurredAt, null);
    }

    private void record(
            GeneratedDocument document,
            DocumentLifecycleAction action,
            DocumentRetentionState fromState,
            String actorId,
            LocalDateTime occurredAt,
            String caseReference) {
        eventRepository.save(DocumentLifecycleEvent.builder()
                .documentId(document.getId())
                .documentFamilyId(document.getDocumentFamilyId())
                .ownerId(document.getUserId())
                .action(action)
                .fromState(fromState)
                .toState(document.getRetentionState())
                .actorId(actor(actorId))
                .policyVersion(properties.getPolicyVersion())
                .caseReference(caseReference)
                .occurredAt(occurredAt)
                .retentionExpiresAt(
                        occurredAt.plusDays(properties.getLifecycleAuditDays()))
                .build());
    }

    private String actor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("Lifecycle actor is required.");
        }
        return actorId.trim();
    }

    private GeneratedDocumentResponse response(GeneratedDocument document) {
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
                .generationMetadata(generationMetadata(document.getGenerationProvenance()))
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

    private GenerationMetadata generationMetadata(GenerationProvenance provenance) {
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
}

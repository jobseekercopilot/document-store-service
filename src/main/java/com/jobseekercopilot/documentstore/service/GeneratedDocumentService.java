package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GeneratedDocumentService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedDocumentService.class);

    private final GeneratedDocumentRepository repository;

    public GeneratedDocumentResponse createDocument(CreateDocumentRequest request) {
        long startedAt = System.nanoTime();
        Integer version = request.getVersion();
        if (version == null && request.getApplicationId() != null && !request.getApplicationId().isBlank()) {
            version = nextVersion(request.getApplicationId(), request.getDocumentType());
        }
        GeneratedDocument document = GeneratedDocument.builder()
                .userId(request.getUserId())
                .jobId(request.getJobId())
                .applicationId(blankToNull(request.getApplicationId()))
                .documentType(request.getDocumentType())
                .title(request.getTitle())
                .content(request.getContent())
                .version(version == null ? 1 : version)
                .active(request.getActive() == null || request.getActive())
                .originalFilename(blankToNull(request.getOriginalFilename()))
                .sourceType(request.getSourceType() == null ? DocumentSourceType.GENERATED : request.getSourceType())
                .createdBy(blankToNull(request.getCreatedBy()))
                .build();

        GeneratedDocument saved = repository.save(document);
        log.info("Generated document metadata saved documentId={} userId={} jobId={} documentType={} titlePresent={} durationMs={}",
                saved.getId(),
                saved.getUserId(),
                saved.getJobId(),
                saved.getDocumentType(),
                saved.getTitle() != null && !saved.getTitle().isBlank(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
    }

    public GeneratedDocumentResponse getDocumentById(UUID id) {
        long startedAt = System.nanoTime();
        GeneratedDocument document = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + id));
        log.info("Generated document metadata loaded documentId={} userId={} documentType={} durationMs={}",
                id,
                document.getUserId(),
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

    public void deleteDocument(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Document not found with id: " + id);
        }
        repository.deleteById(id);
    }

    public GeneratedDocumentResponse activateDocumentVersion(String applicationId, DocumentType documentType, UUID documentId) {
        GeneratedDocument document = repository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with id: " + documentId));
        if (applicationId == null || applicationId.isBlank() || !applicationId.equals(document.getApplicationId())) {
            throw new IllegalArgumentException("Document does not belong to application: " + applicationId);
        }
        if (documentType == null || documentType != document.getDocumentType()) {
            throw new IllegalArgumentException("Document type does not match requested activation type");
        }

        List<GeneratedDocument> activeVersions =
                repository.findByApplicationIdAndDocumentTypeAndActiveTrue(applicationId, documentType);
        activeVersions.forEach(active -> active.setActive(false));
        document.setActive(true);
        activeVersions.add(document);
        repository.saveAll(activeVersions);
        log.info("Document version activated applicationId={} documentType={} documentId={} version={}",
                applicationId,
                documentType,
                documentId,
                document.getVersion());
        return mapToResponse(document);
    }

    public void deactivateApplicationDocuments(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return;
        }
        List<GeneratedDocument> documents = repository.findAll().stream()
                .filter(document -> applicationId.equals(document.getApplicationId()))
                .toList();
        documents.forEach(document -> document.setActive(false));
        repository.saveAll(documents);
        log.info("Application documents deactivated applicationId={} count={}", applicationId, documents.size());
    }

    private Integer nextVersion(String applicationId, DocumentType documentType) {
        return repository.findByApplicationIdAndDocumentTypeOrderByVersionDesc(applicationId, documentType)
                .stream()
                .findFirst()
                .map(GeneratedDocument::getVersion)
                .map(version -> version + 1)
                .orElse(1);
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

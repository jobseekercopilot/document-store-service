package com.jobseekercopilot.documentstore.systemdata;

import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.service.DocumentFileLifecycleService;
import com.jobseekercopilot.documentstore.service.DocumentFileValidator;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import com.jobseekercopilot.documentstore.storage.ObjectKeyFactory;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/system-data")
@SecurityRequirement(name = "environmentDataToken")
@Validated
public class DocumentStoreSystemDataController {
    private final EnvironmentDataGuard guard;
    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;
    private final DocumentObjectStorage objectStorage;
    private final DocumentFileLifecycleService fileLifecycleService;
    private final DocumentFileValidator fileValidator;

    public DocumentStoreSystemDataController(
            EnvironmentDataGuard guard,
            GeneratedDocumentRepository documentRepository,
            ExportedDocumentFileRepository fileRepository,
            DocumentObjectStorage objectStorage,
            DocumentFileLifecycleService fileLifecycleService,
            DocumentFileValidator fileValidator) {
        this.guard = guard;
        this.documentRepository = documentRepository;
        this.fileRepository = fileRepository;
        this.objectStorage = objectStorage;
        this.fileLifecycleService = fileLifecycleService;
        this.fileValidator = fileValidator;
    }

    @PostMapping("/seed/documents")
    @Transactional
    public ResponseEntity<SystemDataResult> seedDocuments(@RequestBody SystemDataDocumentSeedRequest request) {
        guard.requireEnabled();
        var savedDocuments = documentRepository.saveAllAndFlush(request.documents() == null ? List.of() : request.documents());
        var savedFiles = (request.files() == null ? List.<SystemDataDocumentFileSeed>of() : request.files())
                .stream()
                .map(this::seedFile)
                .toList();
        return ResponseEntity.ok(SystemDataResult.success("SEED", savedDocuments.size() + savedFiles.size(), guard.activeEnvironment(), Map.of(
                "documents", savedDocuments.size(),
                "documentVersions", savedDocuments.size(),
                "files", savedFiles.size())));
    }

    @Transactional
    @DeleteMapping("/scenario/{scenarioId}/documents/{userId}")
    public ResponseEntity<SystemDataResult> resetDocuments(@PathVariable String scenarioId, @PathVariable String userId) {
        guard.requireEnabled();
        return resetDocumentsForOwner(scenarioId, userId, Map.of());
    }

    @Transactional
    @DeleteMapping("/v1/runtime-owners/{scenarioId}/identities/{identityKey}/owners/{userId}")
    public ResponseEntity<SystemDataResult> resetRuntimeOwner(
            @PathVariable @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,54}-v[1-9][0-9]{0,6}") String scenarioId,
            @PathVariable @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,54}") String identityKey,
            @PathVariable UUID userId) {
        guard.requireRuntimeOwnerCleanup();
        SyntheticOwnerId.requireMatches(scenarioId, identityKey, userId);
        return resetDocumentsForOwner(scenarioId, userId.toString(), Map.of("identityKey", identityKey));
    }

    private ResponseEntity<SystemDataResult> resetDocumentsForOwner(
            String scenarioId, String userId, Map<String, Object> extraDetails) {
        List<GeneratedDocument> documents = documentRepository.findByUserId(userId);
        List<UUID> documentIds = documents.stream().map(GeneratedDocument::getId).toList();
        int fileCount = documentIds.isEmpty() ? 0 : fileRepository.findByGeneratedDocumentIdIn(documentIds).size();
        if (!documentIds.isEmpty()) {
            fileLifecycleService.deleteForDocuments(documentIds);
        }
        documentRepository.deleteByUserId(userId);
        Map<String, Object> details = new java.util.LinkedHashMap<>(extraDetails);
        details.put("scenarioId", scenarioId);
        details.put("userId", userId);
        details.put("documents", documents.size());
        details.put("documentVersions", documents.size());
        details.put("files", fileCount);
        return ResponseEntity.ok(SystemDataResult.success(
                "RESET", documents.size() + fileCount, guard.activeEnvironment(), details));
    }

    private ExportedDocumentFile seedFile(SystemDataDocumentFileSeed seed) {
        GeneratedDocument document = documentRepository.findById(seed.generatedDocumentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Seeded file must reference a seeded document"));
        byte[] content = seed.fileContent();
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Seeded file content is required");
        }
        UUID fileId = seed.id() == null ? UUID.randomUUID() : seed.id();
        int version = seed.version() == null ? 1 : seed.version();
        FileSource source =
                seed.source() == null ? FileSource.GENERATED : seed.source();
        if (source == FileSource.USER_UPLOADED) {
            fileValidator.validateUserUpload(
                    seed.fileType(), seed.fileName(), seed.mimeType(), content);
        } else {
            fileValidator.validateGenerated(
                    seed.fileType(), seed.fileName(), seed.mimeType(), content);
        }
        String fileName = fileValidator.safeFileName(fileId, seed.fileType());
        String mimeType = fileValidator.canonicalMimeType(seed.fileType());
        String key = ObjectKeyFactory.forFile(document.getId(), fileId, version);
        String sha256 = ObjectIntegrity.sha256(content);
        objectStorage.put(key, content, mimeType, sha256);
        try {
            return fileRepository.saveAndFlush(ExportedDocumentFile.builder()
                    .id(fileId)
                    .generatedDocumentId(document.getId())
                    .ownerId(document.getUserId())
                    .fileType(seed.fileType())
                    .fileName(fileName)
                    .mimeType(mimeType)
                    .source(source)
                    .active(seed.active() == null || seed.active())
                    .version(version)
                    .storageKey(key)
                    .contentSize(content.length)
                    .contentSha256(sha256)
                    .storageStatus(ObjectStorageStatus.AVAILABLE)
                    .createdAt(seed.createdAt())
                    .updatedAt(seed.updatedAt())
                    .build());
        } catch (RuntimeException exception) {
            try {
                objectStorage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    @GetMapping("/verify/documents/{userId}")
    public ResponseEntity<SystemDataResult> verifyDocuments(@PathVariable String userId) {
        guard.requireEnabled();
        return verifyDocumentsForOwner(userId, Map.of());
    }

    @GetMapping("/v1/runtime-owners/{scenarioId}/identities/{identityKey}/owners/{userId}")
    public ResponseEntity<SystemDataResult> verifyRuntimeOwner(
            @PathVariable @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,54}-v[1-9][0-9]{0,6}") String scenarioId,
            @PathVariable @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,54}") String identityKey,
            @PathVariable UUID userId) {
        guard.requireRuntimeOwnerCleanup();
        SyntheticOwnerId.requireMatches(scenarioId, identityKey, userId);
        return verifyDocumentsForOwner(
                userId.toString(), Map.of("scenarioId", scenarioId, "identityKey", identityKey));
    }

    private ResponseEntity<SystemDataResult> verifyDocumentsForOwner(
            String userId, Map<String, Object> extraDetails) {
        List<GeneratedDocument> documents = documentRepository.findByUserId(userId);
        List<UUID> documentIds = documents.stream().map(GeneratedDocument::getId).toList();
        int fileCount = documentIds.isEmpty() ? 0 : fileRepository.findByGeneratedDocumentIdIn(documentIds).size();
        Map<String, Object> details = new java.util.LinkedHashMap<>(extraDetails);
        details.put("userId", userId);
        details.put("documents", documents.size());
        details.put("documentVersions", documents.size());
        details.put("files", fileCount);
        return ResponseEntity.ok(SystemDataResult.success(
                "VERIFY", documents.size() + fileCount, guard.activeEnvironment(), details));
    }
}

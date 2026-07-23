package com.jobseekercopilot.documentstore.systemdata;

import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/system-data")
public class DocumentStoreSystemDataController {
    private final EnvironmentDataGuard guard;
    private final GeneratedDocumentRepository documentRepository;
    private final ExportedDocumentFileRepository fileRepository;

    public DocumentStoreSystemDataController(
            EnvironmentDataGuard guard,
            GeneratedDocumentRepository documentRepository,
            ExportedDocumentFileRepository fileRepository) {
        this.guard = guard;
        this.documentRepository = documentRepository;
        this.fileRepository = fileRepository;
    }

    @PostMapping("/seed/documents")
    @Transactional
    public ResponseEntity<SystemDataResult> seedDocuments(@RequestBody SystemDataDocumentSeedRequest request) {
        guard.requireEnabled();
        var savedDocuments = documentRepository.saveAllAndFlush(request.documents() == null ? List.of() : request.documents());
        var savedFiles = fileRepository.saveAll(request.files() == null ? List.of() : request.files());
        return ResponseEntity.ok(SystemDataResult.success("SEED", savedDocuments.size() + savedFiles.size(), guard.activeEnvironment(), Map.of(
                "documents", savedDocuments.size(),
                "documentVersions", savedDocuments.size(),
                "files", savedFiles.size())));
    }

    @Transactional
    @DeleteMapping("/scenario/{scenarioId}/documents/{userId}")
    public ResponseEntity<SystemDataResult> resetDocuments(@PathVariable String scenarioId, @PathVariable String userId) {
        guard.requireEnabled();
        List<GeneratedDocument> documents = documentRepository.findByUserId(userId);
        List<UUID> documentIds = documents.stream().map(GeneratedDocument::getId).toList();
        int fileCount = documentIds.isEmpty() ? 0 : fileRepository.findByGeneratedDocumentIdIn(documentIds).size();
        if (!documentIds.isEmpty()) {
            fileRepository.deleteByGeneratedDocumentIdIn(documentIds);
        }
        documentRepository.deleteByUserId(userId);
        return ResponseEntity.ok(SystemDataResult.success("RESET", documents.size() + fileCount, guard.activeEnvironment(), Map.of(
                "scenarioId", scenarioId,
                "userId", userId,
                "documents", documents.size(),
                "files", fileCount)));
    }

    @GetMapping("/verify/documents/{userId}")
    public ResponseEntity<SystemDataResult> verifyDocuments(@PathVariable String userId) {
        guard.requireEnabled();
        List<GeneratedDocument> documents = documentRepository.findByUserId(userId);
        List<UUID> documentIds = documents.stream().map(GeneratedDocument::getId).toList();
        int fileCount = documentIds.isEmpty() ? 0 : fileRepository.findByGeneratedDocumentIdIn(documentIds).size();
        return ResponseEntity.ok(SystemDataResult.success("VERIFY", documents.size() + fileCount, guard.activeEnvironment(), Map.of(
                "userId", userId,
                "documents", documents.size(),
                "documentVersions", documents.size(),
                "files", fileCount)));
    }
}

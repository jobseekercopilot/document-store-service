package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.ExportedDocumentFile;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.service.DocumentFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Document Files", description = "Endpoints for storing and downloading exported DOCX and PDF files")
public class DocumentFileController {

    private final DocumentFileService service;

    @PostMapping("/api/v1/document-files")
    @Operation(summary = "Save exported file", description = "Stores exported DOCX or PDF bytes for a generated document")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Exported file saved successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<DocumentFileResponse> createDocumentFile(@Valid @RequestBody CreateDocumentFileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDocumentFile(request));
    }

    @PostMapping(value = "/api/v1/documents/{generatedDocumentId}/files/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload replacement file", description = "Stores a user-uploaded DOCX or PDF replacement for a generated document")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Replacement file saved successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid upload"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<DocumentFileResponse> uploadReplacementFile(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") FileType fileType,
            @RequestParam(value = "source", defaultValue = "USER_UPLOADED") FileSource source) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.uploadReplacementFile(generatedDocumentId, file, fileType, source));
    }

    @GetMapping("/api/v1/document-files/{id}")
    @Operation(summary = "Get exported file metadata")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exported file metadata found"),
            @ApiResponse(responseCode = "404", description = "Exported file not found")
    })
    public ResponseEntity<DocumentFileResponse> getDocumentFileMetadata(
            @Parameter(description = "UUID of the exported file") @PathVariable UUID id) {
        return ResponseEntity.ok(service.getDocumentFileMetadata(id));
    }

    @GetMapping("/api/v1/document-files/{id}/download")
    @Operation(summary = "Download exported file")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exported file bytes"),
            @ApiResponse(responseCode = "404", description = "Exported file not found")
    })
    public ResponseEntity<byte[]> downloadDocumentFile(
            @Parameter(description = "UUID of the exported file") @PathVariable UUID id) {
        ExportedDocumentFile file = service.getDocumentFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName())
                        .build()
                        .toString())
                .body(file.getFileContent());
    }

    @GetMapping("/api/v1/documents/{generatedDocumentId}/files")
    @Operation(summary = "List exported files for a generated document")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exported file metadata list"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<List<DocumentFileResponse>> getFilesForDocument(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId) {
        return ResponseEntity.ok(service.getFilesForDocument(generatedDocumentId));
    }

    @GetMapping("/api/v1/documents/{generatedDocumentId}/files/latest")
    @Operation(summary = "List latest active exported files for a generated document")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Latest active exported file metadata list"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<List<DocumentFileResponse>> getLatestFilesForDocument(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId) {
        return ResponseEntity.ok(service.getLatestFilesForDocument(generatedDocumentId));
    }
}

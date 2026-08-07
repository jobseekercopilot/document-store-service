package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFileDownload;
import com.jobseekercopilot.documentstore.dto.DocumentFileResponse;
import com.jobseekercopilot.documentstore.entity.FileSource;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.ErrorResponse;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.DocumentFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final DocumentOwnerResolver ownerResolver;

    @PostMapping("/api/v1/document-files")
    @Operation(summary = "Save exported file", description = "Stores exported DOCX or PDF bytes for a generated document")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Exported file saved successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields"),
            @ApiResponse(
                    responseCode = "413",
                    description = "Decoded file or archive exceeds a configured safety limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Generated document not found"),
            @ApiResponse(responseCode = "409", description = "Idempotency conflict")
    })
    public ResponseEntity<DocumentFileResponse> createDocumentFile(
            @Valid @RequestBody CreateDocumentFileRequest request,
            @Parameter(description = "Stable retry key; the same key and bytes return the original file")
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Parameter(description = "Required owner context for approved producer identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createDocumentFile(ownerId, request, idempotencyKey));
    }

    @PostMapping(value = "/api/v1/documents/{generatedDocumentId}/files/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload replacement DOCX", description = "Validates and stores a user-uploaded DOCX replacement for a generated document")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Replacement file saved successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid upload"),
            @ApiResponse(
                    responseCode = "413",
                    description = "Upload exceeds a configured safety limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Generated document not found"),
            @ApiResponse(responseCode = "409", description = "Idempotency conflict")
    })
    public ResponseEntity<DocumentFileResponse> uploadReplacementFile(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId,
            @RequestParam("file") MultipartFile file,
            @Parameter(
                    description = "Private beta replacement uploads support DOCX only",
                    schema = @Schema(allowableValues = {"DOCX"}))
            @RequestParam("fileType") FileType fileType,
            @RequestParam(value = "source", defaultValue = "USER_UPLOADED") FileSource source,
            @Parameter(description = "Stable retry key; the same key and bytes return the original file")
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.uploadReplacementFile(
                        ownerId,
                        generatedDocumentId,
                        file,
                        fileType,
                        source,
                        idempotencyKey));
    }

    @PatchMapping("/api/v1/document-files/{id}/active")
    @Operation(
            summary = "Restore an exported file version",
            description = "Atomically makes a retained available version current for its document and file type")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "File version is current"),
            @ApiResponse(responseCode = "404", description = "Exported file version not found or unavailable")
    })
    public ResponseEntity<DocumentFileResponse> activateFileVersion(
            @Parameter(description = "UUID of the retained exported file") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.activateFileVersion(ownerId, id));
    }

    @GetMapping("/api/v1/document-files/{id}")
    @Operation(summary = "Get exported file metadata")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exported file metadata found"),
            @ApiResponse(responseCode = "404", description = "Exported file not found")
    })
    public ResponseEntity<DocumentFileResponse> getDocumentFileMetadata(
            @Parameter(description = "UUID of the exported file") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.getDocumentFileMetadata(ownerId, id));
    }

    @GetMapping("/api/v1/document-files/{id}/download")
    @Operation(
            summary = "Download an exact retained artifact (legacy route)",
            description = "Downloads the owner-authorised retained artifact without activating it or changing document state. Prefer the document/artifact relationship route for new consumers.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Exact retained artifact bytes",
                    headers = {
                            @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            @Header(name = "X-Content-Type-Options", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "Pragma", schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", schema = @Schema(type = "integer", format = "int64"))
                    }),
            @ApiResponse(responseCode = "404", description = "Exported file not found"),
            @ApiResponse(
                    responseCode = "503",
                    description = "Stored file failed integrity or safety validation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<byte[]> downloadDocumentFile(
            @Parameter(description = "UUID of the exported file") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        DocumentFileDownload file = service.downloadDocumentFile(ownerId, id);
        return downloadResponse(file);
    }

    @GetMapping("/api/v1/documents/{generatedDocumentId}/artifacts/{artifactId}/download")
    @Operation(
            summary = "Download an exact retained document artifact",
            description = "Validates the exact document-version/artifact relationship and downloads an available retained artifact, including one on an archived version, without changing state")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Exact retained artifact bytes",
                    headers = {
                            @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            @Header(name = "X-Content-Type-Options", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "Pragma", schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", schema = @Schema(type = "integer", format = "int64"))
                    }),
            @ApiResponse(
                    responseCode = "404",
                    description = "Artifact not found, unavailable, not owned or not related to the document version"),
            @ApiResponse(
                    responseCode = "503",
                    description = "Stored artifact failed integrity or safety validation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<byte[]> downloadDocumentArtifact(
            @Parameter(description = "UUID of the exact document version")
            @PathVariable UUID generatedDocumentId,
            @Parameter(description = "UUID of its exact retained artifact")
            @PathVariable UUID artifactId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        DocumentFileDownload file = service.downloadDocumentArtifact(
                ownerId, generatedDocumentId, artifactId);
        return downloadResponse(file);
    }

    private ResponseEntity<byte[]> downloadResponse(DocumentFileDownload file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName())
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .contentLength(file.content().length)
                .body(file.content());
    }

    @GetMapping("/api/v1/documents/{generatedDocumentId}/files")
    @Operation(summary = "List exported files for a generated document")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exported file metadata list"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<List<DocumentFileResponse>> getFilesForDocument(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.getFilesForDocument(ownerId, generatedDocumentId));
    }

    @GetMapping("/api/v1/documents/{generatedDocumentId}/files/latest")
    @Operation(summary = "List latest active exported files for a generated document")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Latest active exported file metadata list"),
            @ApiResponse(responseCode = "404", description = "Generated document not found")
    })
    public ResponseEntity<List<DocumentFileResponse>> getLatestFilesForDocument(
            @Parameter(description = "UUID of the generated document") @PathVariable UUID generatedDocumentId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.getLatestFilesForDocument(
                ownerId,
                generatedDocumentId));
    }
}

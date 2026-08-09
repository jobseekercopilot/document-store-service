package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.ApplicationDocumentUploadResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.ErrorResponse;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.ApplicationDocumentUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Tag(name = "Application document uploads")
public class ApplicationDocumentUploadController {

    private final ApplicationDocumentUploadService service;
    private final DocumentOwnerResolver ownerResolver;

    @PostMapping(
            value = "/api/v1/applications/{applicationId}/documents/{documentType}/uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Store one secure application-scoped uploaded document version",
            description = "Quarantines, validates, malware-scans and extracts one PDF or DOCX before publishing an approved immutable version. This route has no AI Credit, Payment, CV/Letter Service or LLM dependency.")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upload operation completed or replayed"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed upload context",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Idempotency or rate conflict",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "413",
                    description = "File or request envelope is too large",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApplicationDocumentUploadResponse> upload(
            @PathVariable String applicationId,
            @PathVariable DocumentType documentType,
            @RequestParam String jobId,
            @RequestParam FileType fileType,
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Parameter(description = "Required owner context for the approved producer identity")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.upload(
                ownerId,
                jobId,
                applicationId,
                documentType,
                fileType,
                file,
                idempotencyKey));
    }

    @GetMapping(
            value = "/api/v1/application-document-uploads/{operationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get owner-scoped application upload status")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upload status found"),
            @ApiResponse(responseCode = "404", description = "Upload status not found")
    })
    public ResponseEntity<ApplicationDocumentUploadResponse> get(
            @PathVariable UUID operationId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.get(ownerId, operationId));
    }
}

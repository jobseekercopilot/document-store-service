package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentReferenceResponse;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Generated Documents", description = "Endpoints for storing and retrieving generated CVs and cover letters")
public class GeneratedDocumentController {

    private final GeneratedDocumentService service;
    private final DocumentOwnerResolver ownerResolver;

    @PostMapping
    @Operation(summary = "Create a document", description = "Stores a generated document (CV or cover letter)")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Document created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields"),
            @ApiResponse(responseCode = "409", description = "Version or idempotency conflict")
    })
    public ResponseEntity<GeneratedDocumentResponse> createDocument(
            @Valid @RequestBody CreateDocumentRequest request,
            @Parameter(description = "Stable retry key; the same key and request return the original document")
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, request.getUserId());
        GeneratedDocumentResponse response =
                service.createDocument(ownerId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document by ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Document found"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<GeneratedDocumentResponse> getDocumentById(
            @Parameter(description = "UUID of the document") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        GeneratedDocumentResponse response = service.getDocumentById(ownerId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/reference")
    @Operation(summary = "Get an approved immutable document-version reference")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Approved document reference found"),
            @ApiResponse(responseCode = "404", description = "Document not found for owner"),
            @ApiResponse(responseCode = "409", description = "Document is not approved")
    })
    public ResponseEntity<DocumentReferenceResponse> getDocumentReference(
            @PathVariable UUID id,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.getDocumentReference(ownerId, id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get documents by user ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of documents for the user")
    })
    public ResponseEntity<List<GeneratedDocumentResponse>> getDocumentsByUserId(
            @Parameter(description = "User ID to retrieve documents for") @PathVariable String userId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, userId);
        List<GeneratedDocumentResponse> responses = service.getDocumentsByUserId(ownerId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}/job/{jobId}")
    @Operation(summary = "Get documents by user ID and job ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of documents for the user and job")
    })
    public ResponseEntity<List<GeneratedDocumentResponse>> getDocumentsByUserIdAndJobId(
            @Parameter(description = "User ID") @PathVariable String userId,
            @Parameter(description = "Job ID") @PathVariable String jobId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, userId);
        List<GeneratedDocumentResponse> responses =
                service.getDocumentsByUserIdAndJobId(ownerId, jobId);
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Document deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "UUID of the document to delete") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        service.deleteDocument(ownerId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/applications/{applicationId}/{documentType}/active/{documentId}")
    @Operation(summary = "Activate a document version for an application")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> activateDocumentVersion(
            @PathVariable String applicationId,
            @PathVariable DocumentType documentType,
            @PathVariable UUID documentId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.activateDocumentVersion(
                ownerId,
                applicationId,
                documentType,
                documentId));
    }

    @PatchMapping("/{documentId}/approve")
    @Operation(summary = "Explicitly approve a draft and select it as current")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> approveDocumentVersion(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.approveDocumentVersion(ownerId, documentId));
    }

    @PatchMapping("/{documentId}/current")
    @Operation(summary = "Select an approved version as the current family version")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> selectCurrentDocumentVersion(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.selectCurrentDocumentVersion(ownerId, documentId));
    }

    @PostMapping("/applications/{applicationId}/deactivate")
    @Operation(
            summary = "Deprecated compatibility no-op",
            description = "Current document selection is independent of application lifecycle.",
            deprecated = true)
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<Void> deactivateApplicationDocuments(
            @PathVariable String applicationId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        service.deactivateApplicationDocuments(ownerId, applicationId);
        return ResponseEntity.noContent().build();
    }
}

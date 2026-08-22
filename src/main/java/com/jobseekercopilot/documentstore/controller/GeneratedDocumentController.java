package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.DocumentReferenceResponse;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.dto.DocumentLifecycleEventResponse;
import com.jobseekercopilot.documentstore.dto.LegalHoldRequest;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentstore.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentstore.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentstore.dto.ApplicationWithdrawalCleanupRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import com.jobseekercopilot.documentstore.service.DocumentRetentionService;
import com.jobseekercopilot.documentstore.service.DocumentFamilyHistoryService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Generated Documents", description = "Endpoints for storing and retrieving generated CVs and cover letters")
public class GeneratedDocumentController {

    private final GeneratedDocumentService service;
    private final DocumentRetentionService retentionService;
    private final DocumentFamilyHistoryService familyHistoryService;
    private final DocumentOwnerResolver ownerResolver;

    @GetMapping("/families")
    @Operation(
            summary = "Page owner-scoped document families",
            description = "Returns content-free family summaries with trusted server-owned versions and explicit current state")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentFamilyPageResponse> listDocumentFamilies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(familyHistoryService.listFamilies(
                ownerId, page, size));
    }

    @GetMapping("/families/{documentFamilyId}")
    @Operation(
            summary = "Get complete document-family history",
            description = "Returns newest-first content-free versions and exact safe artifact manifests")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentFamilyHistoryResponse> getDocumentFamilyHistory(
            @PathVariable UUID documentFamilyId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(familyHistoryService.getFamilyHistory(
                ownerId, documentFamilyId));
    }

    @PatchMapping("/families/{documentFamilyId}/current")
    @Operation(
            summary = "Explicitly select a document-family current version",
            description = "Moves only the family pointer using an exact expected pointer and a replay-safe command key")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentFamilyCurrentResponse> selectFamilyCurrent(
            @PathVariable UUID documentFamilyId,
            @Valid @RequestBody SelectFamilyCurrentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(familyHistoryService.selectCurrent(
                ownerId, documentFamilyId, request, idempotencyKey));
    }

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

    @PostMapping("/application-withdrawals")
    @Operation(
            summary = "Atomically soft-delete generated-only application documents",
            description =
                    "Executes one owner-scoped Application Tracker workflow command; replaying the same exact document IDs is safe")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "All selected documents are soft deleted"),
            @ApiResponse(responseCode = "400", description = "Invalid or duplicate document IDs"),
            @ApiResponse(responseCode = "404", description = "A selected document is absent or belongs to another owner"),
            @ApiResponse(responseCode = "409", description = "A selected document is protected from deletion")
    })
    public ResponseEntity<Void> cleanupApplicationWithdrawal(
            @Valid @RequestBody ApplicationWithdrawalCleanupRequest request,
            @Parameter(description = "Required owner context for the Application Tracker producer")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId =
                ownerResolver.resolve(authentication, requestedOwner, null);
        retentionService.softDeleteForApplicationWithdrawal(
                ownerId, request, authentication.getName());
        return ResponseEntity.noContent().build();
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
    @Operation(
            summary = "Soft-delete a document",
            description = "Starts the configured recovery window without immediately removing content or files")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Document is soft deleted"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "UUID of the document to delete") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        service.deleteDocument(ownerId, id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{documentId}/archive")
    @Operation(summary = "Archive an available document")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> archiveDocument(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(retentionService.archive(
                ownerId, documentId, authentication.getName()));
    }

    @PatchMapping("/{documentId}/restore")
    @Operation(
            summary = "Restore an archived or soft-deleted document",
            description = "Restores availability without selecting the version as current")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> restoreDocument(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(retentionService.restore(
                ownerId, documentId, authentication.getName()));
    }

    @GetMapping("/{documentId}/lifecycle-events")
    @Operation(summary = "List the owner-scoped document lifecycle audit")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<List<DocumentLifecycleEventResponse>> lifecycleEvents(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(retentionService.events(ownerId, documentId));
    }

    @PatchMapping("/{documentId}/legal-hold")
    @Operation(
            summary = "Apply or release a legal hold",
            description = "Requires the dedicated retention-administrator service identity")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<GeneratedDocumentResponse> setLegalHold(
            @PathVariable UUID documentId,
            @Valid @RequestBody LegalHoldRequest request,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(retentionService.setLegalHold(
                ownerId,
                documentId,
                request.isActive(),
                request.getReference(),
                authentication.getName()));
    }

    @DeleteMapping("/{documentId}/purge")
    @Operation(
            summary = "Irreversibly purge an eligible document",
            description = "Fail-closed retention-admin operation; disabled until an approved policy is configured")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<Void> purgeDocument(
            @PathVariable UUID documentId,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        retentionService.purge(ownerId, documentId, authentication.getName());
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
    @Operation(
            summary = "Explicitly approve a draft",
            description = "Approval changes lifecycle only and never changes the family current pointer")
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
    @Operation(
            summary = "Legacy select-current command",
            description = "Use /families/{documentFamilyId}/current for concurrency and idempotency protection.",
            deprecated = true)
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

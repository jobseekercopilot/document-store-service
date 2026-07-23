package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.CreateDocumentRequest;
import com.jobseekercopilot.documentstore.dto.GeneratedDocumentResponse;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.service.GeneratedDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping
    @Operation(summary = "Create a document", description = "Stores a generated document (CV or cover letter)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Document created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields")
    })
    public ResponseEntity<GeneratedDocumentResponse> createDocument(@Valid @RequestBody CreateDocumentRequest request) {
        GeneratedDocumentResponse response = service.createDocument(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Document found"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<GeneratedDocumentResponse> getDocumentById(
            @Parameter(description = "UUID of the document") @PathVariable UUID id) {
        GeneratedDocumentResponse response = service.getDocumentById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get documents by user ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of documents for the user")
    })
    public ResponseEntity<List<GeneratedDocumentResponse>> getDocumentsByUserId(
            @Parameter(description = "User ID to retrieve documents for") @PathVariable String userId) {
        List<GeneratedDocumentResponse> responses = service.getDocumentsByUserId(userId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/user/{userId}/job/{jobId}")
    @Operation(summary = "Get documents by user ID and job ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of documents for the user and job")
    })
    public ResponseEntity<List<GeneratedDocumentResponse>> getDocumentsByUserIdAndJobId(
            @Parameter(description = "User ID") @PathVariable String userId,
            @Parameter(description = "Job ID") @PathVariable String jobId) {
        List<GeneratedDocumentResponse> responses = service.getDocumentsByUserIdAndJobId(userId, jobId);
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Document deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Document not found")
    })
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "UUID of the document to delete") @PathVariable UUID id) {
        service.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/applications/{applicationId}/{documentType}/active/{documentId}")
    @Operation(summary = "Activate a document version for an application")
    public ResponseEntity<GeneratedDocumentResponse> activateDocumentVersion(
            @PathVariable String applicationId,
            @PathVariable DocumentType documentType,
            @PathVariable UUID documentId) {
        return ResponseEntity.ok(service.activateDocumentVersion(applicationId, documentType, documentId));
    }

    @PostMapping("/applications/{applicationId}/deactivate")
    @Operation(summary = "Deactivate all documents for an application")
    public ResponseEntity<Void> deactivateApplicationDocuments(@PathVariable String applicationId) {
        service.deactivateApplicationDocuments(applicationId);
        return ResponseEntity.noContent().build();
    }
}

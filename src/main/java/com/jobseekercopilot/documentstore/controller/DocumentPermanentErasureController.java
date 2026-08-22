package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.BackupExpiryAttestationRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureReadinessResponse;
import com.jobseekercopilot.documentstore.dto.PermanentErasureRequest;
import com.jobseekercopilot.documentstore.dto.PermanentErasureResponse;
import com.jobseekercopilot.documentstore.dto.PermanentErasureStatus;
import com.jobseekercopilot.documentstore.dto.RestoreReplayRequest;
import com.jobseekercopilot.documentstore.exception.ErrorResponse;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.DocumentPermanentErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/retention/v1/permanent-erasures")
@RequiredArgsConstructor
public class DocumentPermanentErasureController {

    private final DocumentPermanentErasureService service;
    private final DocumentOwnerResolver ownerResolver;

    @GetMapping("/readiness")
    @Operation(
            summary = "Read permanent-erasure release readiness",
            description = "Returns aggregate fail-closed capability and reconciliation state without owner identities")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<PermanentErasureReadinessResponse> readiness() {
        return ResponseEntity.ok(service.readiness());
    }

    @PutMapping("/{operationId}")
    @Operation(
            summary = "Start or resume exact owner permanent erasure",
            description = "Retention-administrator-only, owner-bound, replay-safe erasure of the exact supplied document set")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Backup retention completed"),
            @ApiResponse(responseCode = "202", description = "Live erasure completed or reconciliation remains pending"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed or duplicate exact scope",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Retention administrator authority is required",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Policy, recovery, legal hold or exact-scope guard blocked erasure",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "Object erasure could not be proven and remains retryable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PermanentErasureResponse> erase(
            @PathVariable UUID operationId,
            @Valid @RequestBody PermanentErasureRequest request,
            @RequestHeader(DocumentOwnerResolver.OWNER_HEADER) String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        PermanentErasureResponse response = service.startOrResume(
                ownerId, operationId, request, authentication.getName());
        return response.status() == PermanentErasureStatus.COMPLETED
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @PutMapping("/{operationId}/backup-expiry-attestation")
    @Operation(
            summary = "Attest expiry of managed backup recovery copies",
            description = "Retention-administrator evidence gate; rejected before the snapshotted backup window ends")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Backup expiry evidence recorded and permanent erasure completed"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed backup-expiry evidence",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Retention administrator authority is required",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Owner-scoped operation was not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Window, policy or live-erasure prerequisite not satisfied",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "Final object-version verification failed and remains retryable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PermanentErasureResponse> attestBackupExpiry(
            @PathVariable UUID operationId,
            @Valid @RequestBody BackupExpiryAttestationRequest request,
            @RequestHeader(DocumentOwnerResolver.OWNER_HEADER) String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.attestBackupExpiry(
                ownerId,
                operationId,
                request,
                authentication.getName()));
    }

    @PutMapping("/{operationId}/restore-replays/{restoreReplayId}")
    @Operation(
            summary = "Replay an immutable permanent-erasure recovery journal after restore",
            description = "Retention-administrator-only restore saga; loads the server-owned immutable journal, durably records the replay obligation, then re-erases exact restored scopes")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Restore replay and fresh backup retention completed"),
            @ApiResponse(responseCode = "202", description = "Restore replay or fresh backup retention remains pending"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed restore-replay request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Retention administrator authority is required",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Owner-scoped retained operation was not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Journal, owner, replay, scope or policy evidence conflicts",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "Immutable journal or exact object erasure remains retryable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PermanentErasureResponse> restoreReplay(
            @PathVariable UUID operationId,
            @PathVariable UUID restoreReplayId,
            @Valid @RequestBody RestoreReplayRequest request,
            @RequestHeader(DocumentOwnerResolver.OWNER_HEADER) String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        PermanentErasureResponse response = service.restoreReplay(
                ownerId,
                operationId,
                restoreReplayId,
                request,
                authentication.getName());
        return response.status() == PermanentErasureStatus.COMPLETED
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{operationId}")
    @Operation(
            summary = "Read exact owner permanent-erasure status",
            description = "Owner-bound status; no raw owner, document, object or approval identifiers are returned")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owner-scoped permanent-erasure status"),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed operation identifier or owner context",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Retention administrator authority is required",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Owner-scoped operation was not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PermanentErasureResponse> status(
            @PathVariable UUID operationId,
            @RequestHeader(DocumentOwnerResolver.OWNER_HEADER) String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(service.status(ownerId, operationId));
    }
}

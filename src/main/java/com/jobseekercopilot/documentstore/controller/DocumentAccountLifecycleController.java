package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.AccountDocumentDeletionResponse;
import com.jobseekercopilot.documentstore.dto.DocumentPersonalDataExport;
import com.jobseekercopilot.documentstore.service.DocumentAccountLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DocumentAccountLifecycleController {

    private final DocumentAccountLifecycleService lifecycleService;

    @GetMapping("/api/v1/documents/account-export")
    @Operation(summary = "Export current owner's document data")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DocumentPersonalDataExport> export(
            @AuthenticationPrincipal Jwt token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.export(token.getSubject()));
    }

    @PostMapping("/internal/account-lifecycle/recoverable-delete")
    @Hidden
    @Operation(summary = "Apply DOC-09 recoverable deletion for an account")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AccountDocumentDeletionResponse> recoverablyDelete(
            @AuthenticationPrincipal Jwt token) {
        return ResponseEntity.ok(lifecycleService.recoverablyDelete(
                token.getSubject(), token.getClaimAsString("operation_id")));
    }
}

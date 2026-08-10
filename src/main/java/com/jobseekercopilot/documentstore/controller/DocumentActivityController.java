package com.jobseekercopilot.documentstore.controller;

import com.jobseekercopilot.documentstore.dto.DocumentActivityPageResponse;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.service.DocumentActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-activity")
@RequiredArgsConstructor
public class DocumentActivityController {

    private final DocumentActivityService activityService;
    private final DocumentOwnerResolver ownerResolver;

    @GetMapping
    @Operation(
            summary = "Page owner-scoped content-free document activity",
            description = "Returns only safe document family, type, source, trusted version, result and time metadata")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentActivityPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestHeader(value = DocumentOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner, null);
        return ResponseEntity.ok(activityService.list(ownerId, page, size));
    }
}

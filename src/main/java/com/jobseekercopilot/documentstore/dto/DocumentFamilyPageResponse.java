package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One bounded page of owner-scoped document families")
public record DocumentFamilyPageResponse(
        List<DocumentFamilySummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}

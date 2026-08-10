package com.jobseekercopilot.documentstore.dto;

import java.util.List;

public record DocumentActivityPageResponse(
        List<DocumentActivityEventResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}

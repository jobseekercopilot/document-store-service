package com.jobseekercopilot.documentstore.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    @Test
    void sensitivePathSegmentsAreNeverReturnedAsLogRoutes() {
        assertThat(CorrelationIdFilter.safeRoute(
                        "/api/v1/documents/550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo("/api/v1/documents/**");
        assertThat(CorrelationIdFilter.safeRoute(
                        "/api/v1/document-files/550e8400-e29b-41d4-a716-446655440000/download"))
                .isEqualTo("/api/v1/document-files/**");
        assertThat(CorrelationIdFilter.safeRoute(
                        "/internal/system-data/verify/documents/user@example.test"))
                .isEqualTo("/internal/system-data/**");
    }
}

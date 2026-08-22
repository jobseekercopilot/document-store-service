package com.jobseekercopilot.documentstore.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

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

    @Test
    void safeIncomingCorrelationIsReturnedAvailableDownstreamAndCleared()
            throws Exception {
        CorrelationIdFilter filter =
                new CorrelationIdFilter("document-store-service");
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/documents/private-id");
        request.addHeader(
                CorrelationIdFilter.HEADER_NAME, "synthetic-correlation-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> downstreamCorrelation = new AtomicReference<>();
        AtomicReference<String> downstreamService = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            downstreamCorrelation.set(
                    MDC.get(CorrelationIdFilter.MDC_KEY));
            downstreamService.set(
                    MDC.get(CorrelationIdFilter.SERVICE_MDC_KEY));
        });

        assertThat(downstreamCorrelation.get())
                .isEqualTo("synthetic-correlation-123");
        assertThat(downstreamService.get()).isEqualTo("document-store-service");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo("synthetic-correlation-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationIdFilter.SERVICE_MDC_KEY)).isNull();
    }

    @Test
    void unsafeIncomingCorrelationIsNotReflected() throws Exception {
        CorrelationIdFilter filter =
                new CorrelationIdFilter("document-store-service");
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/documents/private-id");
        request.addHeader(
                CorrelationIdFilter.HEADER_NAME,
                "raw owner@example.test/unsafe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isNotEqualTo("raw owner@example.test/unsafe")
                .matches("[a-f0-9-]{36}");
    }

    @Test
    void outboundHttpClientsPropagateOnlyTheMdcCorrelation() {
        RestTemplate restTemplate = new RestTemplate();
        new CorrelationIdHttpClientConfig()
                .correlationIdRestTemplateCustomizer()
                .customize(restTemplate);
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://synthetic-downstream.test/health"))
                .andExpect(header(
                        CorrelationIdFilter.HEADER_NAME,
                        "synthetic-outbound-456"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MDC.put(CorrelationIdFilter.MDC_KEY, "synthetic-outbound-456");
        try {
            restTemplate.getForEntity(
                    "http://synthetic-downstream.test/health", String.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        server.verify();
    }
}

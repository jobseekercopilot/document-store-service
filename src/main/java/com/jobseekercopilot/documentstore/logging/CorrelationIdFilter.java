package com.jobseekercopilot.documentstore.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String SERVICE_MDC_KEY = "serviceName";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final int MAXIMUM_CORRELATION_ID_LENGTH = 64;

    private final String serviceName;

    public CorrelationIdFilter(@Value("${spring.application.name:document-store-service}") String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (!isSafeCorrelationId(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        long startedAt = System.nanoTime();
        MDC.put(MDC_KEY, correlationId);
        MDC.put(SERVICE_MDC_KEY, serviceName);
        response.setHeader(HEADER_NAME, correlationId);
        String route = safeRoute(request.getRequestURI());

        try {
            log.info(
                    "service={} request started method={} route={}",
                    serviceName,
                    request.getMethod(),
                    route);
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("service={} request completed method={} route={} status={} durationMs={}",
                    serviceName,
                    request.getMethod(),
                    route,
                    response.getStatus(),
                    durationMs);
            MDC.remove(MDC_KEY);
            MDC.remove(SERVICE_MDC_KEY);
        }
    }

    private static boolean isSafeCorrelationId(String value) {
        if (!StringUtils.hasText(value) || value.length() > MAXIMUM_CORRELATION_ID_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '-'
                    && character != '_'
                    && character != '.') {
                return false;
            }
        }
        return true;
    }

    public static String safeRoute(String requestUri) {
        if (requestUri == null) {
            return "unknown";
        }
        if (requestUri.startsWith("/api/v1/document-files")) {
            return "/api/v1/document-files/**";
        }
        if (requestUri.startsWith("/api/v1/documents")) {
            return "/api/v1/documents/**";
        }
        if (requestUri.startsWith("/internal/system-data")) {
            return "/internal/system-data/**";
        }
        if (requestUri.startsWith("/actuator/health")) {
            return "/actuator/health";
        }
        if (requestUri.startsWith("/v3/api-docs")) {
            return "/v3/api-docs/**";
        }
        if (requestUri.startsWith("/swagger-ui")) {
            return "/swagger-ui/**";
        }
        return "other";
    }
}

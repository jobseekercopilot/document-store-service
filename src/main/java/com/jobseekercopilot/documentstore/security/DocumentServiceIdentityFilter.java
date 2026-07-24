package com.jobseekercopilot.documentstore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DocumentServiceIdentityFilter extends OncePerRequestFilter {

    public static final String SERVICE_HEADER = "X-Service-Token";
    public static final String ENVIRONMENT_DATA_HEADER = "X-Environment-Data-Token";

    private final DocumentSecurityCredentials credentials;

    public DocumentServiceIdentityFilter(DocumentSecurityCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/system-data/")) {
            authenticateEnvironmentData(request);
        } else if (!StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            authenticateService(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateService(HttpServletRequest request) {
        if (Collections.list(request.getHeaders(DocumentOwnerResolver.OWNER_HEADER)).size() > 1) {
            return;
        }
        singleHeader(request, SERVICE_HEADER)
                .flatMap(credentials::authorityForServiceToken)
                .ifPresent(this::authenticate);
    }

    private void authenticateEnvironmentData(HttpServletRequest request) {
        singleHeader(request, ENVIRONMENT_DATA_HEADER)
                .filter(credentials::matchesEnvironmentDataToken)
                .ifPresent(ignored -> authenticate(DocumentAuthorities.ENVIRONMENT_DATA));
    }

    private Optional<String> singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 && StringUtils.hasText(values.get(0))
                ? Optional.of(values.get(0))
                : Optional.empty();
    }

    private void authenticate(String authority) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                authority.toLowerCase(),
                null,
                List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

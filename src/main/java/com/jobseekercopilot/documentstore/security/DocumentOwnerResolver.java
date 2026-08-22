package com.jobseekercopilot.documentstore.security;

import com.jobseekercopilot.documentstore.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DocumentOwnerResolver {

    public static final String OWNER_HEADER = "X-Document-Owner";

    public String resolve(
            Authentication authentication,
            String ownerContext,
            String requestedOwner) {
        if (hasAuthority(authentication, DocumentAuthorities.USER)) {
            String subject = authentication.getName();
            if (!StringUtils.hasText(subject)) {
                throw new AccessDeniedException("Authenticated subject is required");
            }
            requireMatch(subject, ownerContext);
            requireMatch(subject, requestedOwner);
            return subject;
        }

        if (hasAuthority(authentication, DocumentAuthorities.PRODUCER)
                || hasAuthority(authentication, DocumentAuthorities.READER)
                || hasAuthority(authentication, DocumentAuthorities.RETENTION_ADMIN)) {
            if (!StringUtils.hasText(ownerContext)) {
                throw new IllegalArgumentException(
                        "Document owner is required for service requests.");
            }
            String owner = ownerContext.trim();
            requireMatch(owner, requestedOwner);
            return owner;
        }

        throw new AccessDeniedException("Document authority is required");
    }

    private void requireMatch(String owner, String requestedOwner) {
        if (StringUtils.hasText(requestedOwner) && !owner.equals(requestedOwner)) {
            throw ResourceNotFoundException.documentNotFound();
        }
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}

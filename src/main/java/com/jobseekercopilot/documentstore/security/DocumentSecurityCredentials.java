package com.jobseekercopilot.documentstore.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentSecurityCredentials {

    static final int MINIMUM_TOKEN_BYTES = 32;

    private final byte[] producerToken;
    private final byte[] readerToken;
    private final byte[] retentionAdminToken;
    private final byte[] environmentDataToken;

    public DocumentSecurityCredentials(
            @Value("${document-store.security.service-identity.producer-token}")
            String producerToken,
            @Value("${document-store.security.service-identity.reader-token}")
            String readerToken,
            @Value("${document-store.security.service-identity.retention-admin-token}")
            String retentionAdminToken,
            @Value("${document-store.security.environment-data-token}")
            String environmentDataToken) {
        this.producerToken = validate(producerToken, "Document producer token");
        this.readerToken = validate(readerToken, "Document reader token");
        this.retentionAdminToken =
                validate(retentionAdminToken, "Document retention administrator token");
        this.environmentDataToken = validate(environmentDataToken, "Environment-data token");
        requireDistinct(this.producerToken, this.readerToken);
        requireDistinct(this.producerToken, this.retentionAdminToken);
        requireDistinct(this.producerToken, this.environmentDataToken);
        requireDistinct(this.readerToken, this.retentionAdminToken);
        requireDistinct(this.readerToken, this.environmentDataToken);
        requireDistinct(this.retentionAdminToken, this.environmentDataToken);
    }

    public Optional<String> authorityForServiceToken(String candidate) {
        if (matches(producerToken, candidate)) {
            return Optional.of(DocumentAuthorities.PRODUCER);
        }
        if (matches(readerToken, candidate)) {
            return Optional.of(DocumentAuthorities.READER);
        }
        if (matches(retentionAdminToken, candidate)) {
            return Optional.of(DocumentAuthorities.RETENTION_ADMIN);
        }
        return Optional.empty();
    }

    public boolean matchesEnvironmentDataToken(String candidate) {
        return matches(environmentDataToken, candidate);
    }

    private static byte[] validate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must contain at least 32 bytes.");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(label + " must contain at least 32 bytes.");
        }
        return bytes;
    }

    private static void requireDistinct(byte[] first, byte[] second) {
        if (MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException("Document security tokens must be distinct.");
        }
    }

    private static boolean matches(byte[] expected, String candidate) {
        return candidate != null
                && MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8));
    }
}

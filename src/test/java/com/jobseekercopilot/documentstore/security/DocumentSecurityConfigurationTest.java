package com.jobseekercopilot.documentstore.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentSecurityConfigurationTest {

    private static final String PRODUCER = "producer-token-with-at-least-32-bytes";
    private static final String READER = "reader-token-with-at-least-32-bytes-value";
    private static final String RETENTION =
            "retention-token-with-at-least-32-bytes";
    private static final String ENVIRONMENT = "environment-token-with-at-least-32-bytes";

    @Test
    void serviceCredentialsResolveOnlyTheirOwnAuthority() {
        DocumentSecurityCredentials credentials =
                new DocumentSecurityCredentials(PRODUCER, READER, RETENTION, ENVIRONMENT);

        assertThat(credentials.authorityForServiceToken(PRODUCER))
                .contains(DocumentAuthorities.PRODUCER);
        assertThat(credentials.authorityForServiceToken(READER))
                .contains(DocumentAuthorities.READER);
        assertThat(credentials.authorityForServiceToken(RETENTION))
                .contains(DocumentAuthorities.RETENTION_ADMIN);
        assertThat(credentials.authorityForServiceToken("wrong-token")).isEmpty();
        assertThat(credentials.matchesEnvironmentDataToken(ENVIRONMENT)).isTrue();
        assertThat(credentials.matchesEnvironmentDataToken(PRODUCER)).isFalse();
    }

    @Test
    void serviceCredentialsRejectMissingShortOrSharedTokens() {
        assertThatThrownBy(() ->
                new DocumentSecurityCredentials("", READER, RETENTION, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document producer token must contain at least 32 bytes.");
        assertThatThrownBy(() ->
                new DocumentSecurityCredentials("short", READER, RETENTION, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document producer token must contain at least 32 bytes.");
        assertThatThrownBy(() ->
                new DocumentSecurityCredentials(
                        PRODUCER, PRODUCER, RETENTION, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document security tokens must be distinct.");
        assertThatThrownBy(() ->
                new DocumentSecurityCredentials(PRODUCER, READER, READER, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document security tokens must be distinct.");
        assertThatThrownBy(() ->
                new DocumentSecurityCredentials(
                        PRODUCER, READER, RETENTION, RETENTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document security tokens must be distinct.");
    }

    @Test
    void jwtVerificationConfigurationFailsClosed() {
        DocumentSecurityConfig.validateConfiguration(
                "https://auth.example.test/.well-known/jwks.json",
                "issuer",
                "audience");

        assertThatThrownBy(() ->
                DocumentSecurityConfig.validateConfiguration(
                        "file:///tmp/jwks.json",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document Store JWT verification configuration is invalid");
        assertThatThrownBy(() ->
                DocumentSecurityConfig.validateConfiguration(
                        "https://user@example.test/jwks",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                DocumentSecurityConfig.validateConfiguration(
                        "https://auth.example.test/jwks",
                        " ",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
    }
}

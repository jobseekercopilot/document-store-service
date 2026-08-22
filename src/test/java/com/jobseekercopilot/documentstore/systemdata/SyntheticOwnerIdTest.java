package com.jobseekercopilot.documentstore.systemdata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SyntheticOwnerIdTest {
    @Test
    void allowsAuthenticationOwnedRegistrationUuid() {
        assertDoesNotThrow(() -> SyntheticOwnerId.requireMatches(
                "registration-clean-v1", "registration-primary", UUID.randomUUID()));
    }

    @Test
    void rejectsUnexpectedRuntimeOwnerForOtherNamedStates() {
        assertThrows(ResponseStatusException.class, () -> SyntheticOwnerId.requireMatches(
                "demo-ready-v1", "alex-taylor", UUID.randomUUID()));
    }
}

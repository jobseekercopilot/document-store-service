package com.jobseekercopilot.documentstore.systemdata;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SyntheticOwnerId {
    private static final String NAMESPACE = "job-seeker-copilot:system-data:";

    private SyntheticOwnerId() {
    }

    static void requireMatches(
            String scenarioId, String identityKey, UUID suppliedOwnerId) {
        UUID expected = UUID.nameUUIDFromBytes((NAMESPACE
                + scenarioId
                + ":"
                + identityKey
                + ":user").getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(suppliedOwnerId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Owner does not match the named-state synthetic identity");
        }
    }
}

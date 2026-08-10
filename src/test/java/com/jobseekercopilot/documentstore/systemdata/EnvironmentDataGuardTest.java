package com.jobseekercopilot.documentstore.systemdata;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.documentstore.config.EnvironmentDataProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class EnvironmentDataGuardTest {

    @Test
    void runtimeOwnerCleanupRequiresEnabledIsolatedNonProductionProfile() {
        assertThatThrownBy(() -> guard(false, true, "e2e")
                        .requireRuntimeOwnerCleanup())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard(true, false, "e2e")
                        .requireRuntimeOwnerCleanup())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isolated database");
        assertThatThrownBy(() -> guard(true, true, "production")
                        .requireRuntimeOwnerCleanup())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("forbidden in production");
        assertThatCode(() -> guard(true, true, "e2e")
                        .requireRuntimeOwnerCleanup())
                .doesNotThrowAnyException();
    }

    @Test
    void syntheticOwnerValidationRejectsAnUnrelatedOwner() {
        assertThatThrownBy(() -> SyntheticOwnerId.requireMatches(
                        "cross-user-security-v1",
                        "claimant-a",
                        UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("synthetic identity");
    }

    private EnvironmentDataGuard guard(
            boolean enabled, boolean isolated, String profile) {
        EnvironmentDataProperties properties = new EnvironmentDataProperties();
        properties.setEnabled(enabled);
        properties.setIsolatedDatabase(isolated);
        properties.setAllowedEnvironments(List.of(profile));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new EnvironmentDataGuard(properties, environment);
    }
}

package com.jobseekercopilot.documentstore.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentRetentionPropertiesTest {

    @Test
    void irreversiblePurgeFailsClosedWithoutBothEnablementAndApprovedPolicy() {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();

        assertThatThrownBy(properties::requireApprovedPurgePolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Irreversible document purge is disabled");
        assertThatThrownBy(properties::requireApprovedMaintenancePolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Retention maintenance is disabled");

        properties.setPurgeEnabled(true);
        properties.setMaintenanceEnabled(true);
        assertThatThrownBy(properties::requireApprovedPurgePolicy)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(properties::requireApprovedMaintenancePolicy)
                .isInstanceOf(IllegalStateException.class);

        properties.setPolicyVersion("approved-policy-v1");
        properties.requireApprovedPurgePolicy();
        properties.requireApprovedMaintenancePolicy();
    }

    @Test
    void permanentErasureRequiresEveryIndependentCapabilityAndStrongFingerprintKey() {
        DocumentRetentionProperties properties = new DocumentRetentionProperties();
        properties.setPurgeEnabled(true);
        properties.setPermanentErasureEnabled(true);
        properties.setPermanentErasureWriteFenceEnabled(true);
        properties.setVersionedObjectErasureEnabled(true);
        properties.setPolicyVersion("reviewed-retention-2026-08");
        properties.setBackupRetentionPolicyVersion("reviewed-backups-35d-v1");
        properties.setErasureFingerprintKey("owner-fingerprint-secret-key-000001");

        assertThatCode(properties::requireApprovedPermanentErasurePolicy)
                .doesNotThrowAnyException();

        properties.setVersionedObjectErasureEnabled(false);
        assertThatThrownBy(properties::requireApprovedPermanentErasurePolicy)
                .hasMessageContaining("Permanent account erasure is disabled");
    }
}

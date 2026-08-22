package com.jobseekercopilot.documentstore.storage;

import java.util.UUID;

public final class PermanentErasureJournalKeys {

    public static final String PREFIX = "permanent-erasures/v1/";

    private PermanentErasureJournalKeys() {
    }

    public static String forOperation(UUID operationId) {
        if (operationId == null) {
            throw new IllegalArgumentException("Permanent-erasure operation ID is required.");
        }
        return PREFIX + operationId + ".json";
    }
}

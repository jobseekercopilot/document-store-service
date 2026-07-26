package com.jobseekercopilot.documentstore.storage;

import java.util.UUID;

public final class ObjectKeyFactory {
    private ObjectKeyFactory() {
    }

    public static String forFile(UUID generatedDocumentId, UUID fileId, int version) {
        return "documents/" + generatedDocumentId + "/files/" + fileId + "/v" + version;
    }
}

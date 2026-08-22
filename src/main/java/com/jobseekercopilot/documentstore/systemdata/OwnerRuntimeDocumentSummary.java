package com.jobseekercopilot.documentstore.systemdata;

import java.util.LinkedHashMap;
import java.util.Map;

public record OwnerRuntimeDocumentSummary(
        int documents,
        int files,
        int uploads,
        int commands,
        int storageOperations,
        int events,
        int tombstoneAssociations) {

    public int total() {
        return documents
                + files
                + uploads
                + commands
                + storageOperations
                + events
                + tombstoneAssociations;
    }

    public Map<String, Object> details(String scenarioId, String identityKey) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scenarioId", scenarioId);
        details.put("identityKey", identityKey);
        details.put("documents", documents);
        details.put("documentVersions", documents);
        details.put("files", files);
        details.put("uploads", uploads);
        details.put("commands", commands);
        details.put("storageOperations", storageOperations);
        details.put("events", events);
        details.put("tombstoneAssociations", tombstoneAssociations);
        return Map.copyOf(details);
    }
}

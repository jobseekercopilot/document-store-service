package com.jobseekercopilot.documentstore.systemdata;

import java.util.List;
import java.util.Map;

public record SystemDataResult(
        String service,
        String operation,
        String status,
        int recordsAffected,
        String activeEnvironment,
        Map<String, Object> details,
        List<String> warnings) {
    public static SystemDataResult success(String operation, int recordsAffected, String activeEnvironment, Map<String, Object> details) {
        return new SystemDataResult("document-store-service", operation, "SUCCESS", recordsAffected, activeEnvironment, details, List.of());
    }
}

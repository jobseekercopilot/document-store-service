package com.jobseekercopilot.documentstore.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException documentNotFound() {
        return new ResourceNotFoundException("Document not found.");
    }

    public static ResourceNotFoundException documentFileNotFound() {
        return new ResourceNotFoundException("Document file not found.");
    }
}

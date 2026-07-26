package com.jobseekercopilot.documentstore.exception;

public class DocumentFileTooLargeException extends IllegalArgumentException {

    public DocumentFileTooLargeException(String message) {
        super(message);
    }
}

ALTER TABLE generated_documents
    ADD COLUMN original_content_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN original_content_size BIGINT;
ALTER TABLE generated_documents
    ADD COLUMN original_artifact_id UUID;
ALTER TABLE generated_documents
    ADD COLUMN original_file_type VARCHAR(16);
ALTER TABLE generated_documents
    ADD COLUMN extraction_state VARCHAR(24);

ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_original_sha256
        CHECK (original_content_sha256 IS NULL OR CHAR_LENGTH(original_content_sha256) = 64);
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_original_size
        CHECK (original_content_size IS NULL OR original_content_size > 0);
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_original_file_type
        CHECK (original_file_type IS NULL OR original_file_type IN ('DOCX', 'PDF'));
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_extraction_state
        CHECK (extraction_state IS NULL OR extraction_state IN ('SUCCEEDED', 'NO_TEXT'));
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_upload_metadata
        CHECK (
            (
                original_content_sha256 IS NULL
                AND original_content_size IS NULL
                AND original_artifact_id IS NULL
                AND original_file_type IS NULL
                AND extraction_state IS NULL
            )
            OR (
                original_content_sha256 IS NOT NULL
                AND original_content_size IS NOT NULL
                AND original_artifact_id IS NOT NULL
                AND original_file_type IS NOT NULL
                AND extraction_state IS NOT NULL
            )
        );

CREATE TABLE application_document_uploads (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    job_id VARCHAR(255) NOT NULL,
    application_id VARCHAR(255) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    state VARCHAR(32) NOT NULL,
    original_sha256 VARCHAR(64) NOT NULL,
    original_size BIGINT NOT NULL,
    extracted_text_sha256 VARCHAR(64),
    extraction_state VARCHAR(24),
    quarantine_key VARCHAR(512),
    document_id UUID,
    artifact_id UUID,
    failure_code VARCHAR(64),
    failure_message VARCHAR(255),
    scanner_engine VARCHAR(32),
    scanner_version VARCHAR(64),
    scanner_signature_at TIMESTAMP,
    processing_started_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_application_document_upload_owner_key
        UNIQUE (owner_id, idempotency_key),
    CONSTRAINT ck_application_document_upload_request_sha
        CHECK (CHAR_LENGTH(request_sha256) = 64),
    CONSTRAINT ck_application_document_upload_original_sha
        CHECK (CHAR_LENGTH(original_sha256) = 64),
    CONSTRAINT ck_application_document_upload_text_sha
        CHECK (extracted_text_sha256 IS NULL OR CHAR_LENGTH(extracted_text_sha256) = 64),
    CONSTRAINT ck_application_document_upload_size
        CHECK (original_size > 0),
    CONSTRAINT ck_application_document_upload_document_type
        CHECK (document_type IN ('CV', 'COVER_LETTER')),
    CONSTRAINT ck_application_document_upload_file_type
        CHECK (file_type IN ('DOCX', 'PDF')),
    CONSTRAINT ck_application_document_upload_state
        CHECK (state IN (
            'RECEIVED', 'QUARANTINED', 'SCANNING', 'SCANNED_CLEAN',
            'EXTRACTING', 'READY', 'REJECTED', 'FAILED', 'SCAN_UNAVAILABLE'
        )),
    CONSTRAINT ck_application_document_upload_extraction
        CHECK (extraction_state IS NULL OR extraction_state IN ('SUCCEEDED', 'NO_TEXT')),
    CONSTRAINT ck_application_document_upload_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX ix_application_document_upload_owner
    ON application_document_uploads(owner_id, created_at);
CREATE INDEX ix_application_document_upload_cleanup
    ON application_document_uploads(state, updated_at);
CREATE INDEX ix_application_document_upload_context
    ON application_document_uploads(owner_id, application_id, document_type, created_at);

ALTER TABLE document_activity_events
    DROP CONSTRAINT chk_document_activity_event_type;
ALTER TABLE document_activity_events
    ADD CONSTRAINT chk_document_activity_event_type CHECK (event_type IN (
        'DOCUMENT_VERSION_CREATED',
        'DOCUMENT_VERSION_DOWNLOADED',
        'DOCUMENT_CURRENT_VERSION_CHANGED',
        'DOCUMENT_VERSION_ARCHIVED',
        'DOCUMENT_VERSION_RESTORED',
        'DOCUMENT_UPLOADED'
    ));

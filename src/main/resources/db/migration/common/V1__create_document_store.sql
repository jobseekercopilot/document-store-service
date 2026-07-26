CREATE TABLE generated_documents (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    job_id VARCHAR(255) NOT NULL,
    application_id VARCHAR(255),
    document_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    original_filename VARCHAR(255),
    source_type VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_by VARCHAR(255),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_generated_documents_type
        CHECK (document_type IN ('CV', 'COVER_LETTER')),
    CONSTRAINT ck_generated_documents_source
        CHECK (source_type IN ('GENERATED', 'UPLOADED')),
    CONSTRAINT ck_generated_documents_version
        CHECK (version > 0),
    CONSTRAINT ck_generated_documents_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX ix_generated_documents_owner
    ON generated_documents (user_id);
CREATE INDEX ix_generated_documents_owner_job
    ON generated_documents (user_id, job_id);
CREATE INDEX ix_generated_documents_owner_application
    ON generated_documents (user_id, application_id);
CREATE INDEX ix_generated_documents_version_lookup
    ON generated_documents (user_id, application_id, document_type, version DESC);

CREATE TABLE exported_document_files (
    id UUID PRIMARY KEY,
    generated_document_id UUID NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    file_content BYTEA NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_exported_document_files_document
        FOREIGN KEY (generated_document_id)
        REFERENCES generated_documents (id),
    CONSTRAINT ck_exported_document_files_type
        CHECK (file_type IN ('DOCX', 'PDF')),
    CONSTRAINT ck_exported_document_files_source
        CHECK (source IN ('GENERATED', 'USER_UPLOADED')),
    CONSTRAINT ck_exported_document_files_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX ix_exported_document_files_document
    ON exported_document_files (generated_document_id);
CREATE INDEX ix_exported_document_files_active_lookup
    ON exported_document_files (generated_document_id, file_type, active, updated_at DESC);

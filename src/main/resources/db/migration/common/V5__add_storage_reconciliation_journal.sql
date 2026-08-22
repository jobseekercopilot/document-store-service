CREATE TABLE document_storage_operations (
    file_id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    generated_document_id UUID NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    file_version INTEGER NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    operation_key VARCHAR(128),
    request_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_document_storage_operations_file_type
        CHECK (file_type IN ('DOCX', 'PDF')),
    CONSTRAINT ck_document_storage_operations_version
        CHECK (file_version > 0),
    CONSTRAINT ck_document_storage_operations_request_sha256
        CHECK (CHAR_LENGTH(request_sha256) = 64),
    CONSTRAINT ck_document_storage_operations_state
        CHECK (state IN ('PREPARED', 'COMMITTED', 'ROLLED_BACK')),
    CONSTRAINT ck_document_storage_operations_timestamps
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_document_storage_operations_storage_key
    ON document_storage_operations (storage_key);
CREATE UNIQUE INDEX ux_document_storage_operations_version
    ON document_storage_operations (
        generated_document_id,
        file_type,
        file_version
    );
CREATE UNIQUE INDEX ux_document_storage_operations_idempotency
    ON document_storage_operations (owner_id, operation_key);
CREATE INDEX ix_document_storage_operations_reconciliation
    ON document_storage_operations (state, updated_at, file_id);

CREATE TABLE document_storage_reconciliation_cursors (
    cursor_name VARCHAR(64) PRIMARY KEY,
    after_key VARCHAR(512),
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL
);

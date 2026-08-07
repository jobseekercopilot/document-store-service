CREATE TABLE document_current_commands (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    document_family_id UUID NOT NULL,
    current_document_id UUID NOT NULL,
    current_version INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_document_current_commands_sha256
        CHECK (CHAR_LENGTH(request_sha256) = 64),
    CONSTRAINT ck_document_current_commands_version
        CHECK (current_version > 0),
    CONSTRAINT ux_document_current_commands_owner_key
        UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX ix_document_current_commands_family
    ON document_current_commands (owner_id, document_family_id, created_at DESC);

CREATE INDEX ix_generated_documents_owner_family_summary
    ON generated_documents (user_id, updated_at DESC, document_family_id);

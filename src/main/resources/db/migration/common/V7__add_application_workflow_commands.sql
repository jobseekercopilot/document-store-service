CREATE TABLE document_application_workflow_commands (
    operation_id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    application_id UUID NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_document_application_workflow_command_type
        CHECK (command_type IN ('GENERATED_WITHDRAWAL')),
    CONSTRAINT ck_document_application_workflow_command_sha
        CHECK (LENGTH(request_sha256) = 64),
    CONSTRAINT ck_document_application_workflow_command_status
        CHECK (status IN ('COMPLETED'))
);

CREATE UNIQUE INDEX ux_document_application_workflow_owner_operation
    ON document_application_workflow_commands (owner_id, operation_id);

CREATE INDEX ix_document_application_workflow_application
    ON document_application_workflow_commands (
        owner_id,
        application_id,
        created_at
    );

ALTER TABLE generated_documents
    ADD COLUMN retention_state VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE generated_documents
    ADD COLUMN archived_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN archived_by VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN deleted_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN deleted_by VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN purge_eligible_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE generated_documents
    ADD COLUMN legal_hold_reference VARCHAR(128);
ALTER TABLE generated_documents
    ADD COLUMN legal_hold_updated_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN legal_hold_updated_by VARCHAR(255);

ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_retention_state
        CHECK (retention_state IN ('AVAILABLE', 'ARCHIVED', 'DELETED'));
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_available_retention
        CHECK (
            retention_state <> 'AVAILABLE'
            OR (
                archived_at IS NULL
                AND archived_by IS NULL
                AND deleted_at IS NULL
                AND deleted_by IS NULL
                AND purge_eligible_at IS NULL
            )
        );
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_archived_retention
        CHECK (
            retention_state <> 'ARCHIVED'
            OR (
                archived_at IS NOT NULL
                AND archived_by IS NOT NULL
                AND deleted_at IS NULL
                AND deleted_by IS NULL
                AND purge_eligible_at IS NULL
            )
        );
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_deleted_retention
        CHECK (
            retention_state <> 'DELETED'
            OR (
                deleted_at IS NOT NULL
                AND deleted_by IS NOT NULL
                AND purge_eligible_at IS NOT NULL
            )
        );
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_retained_not_current
        CHECK (retention_state = 'AVAILABLE' OR NOT active);
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_legal_hold
        CHECK (
            (
                NOT legal_hold
                AND legal_hold_reference IS NULL
                AND legal_hold_updated_at IS NULL
                AND legal_hold_updated_by IS NULL
            )
            OR
            (
                legal_hold
                AND legal_hold_reference IS NOT NULL
                AND legal_hold_updated_at IS NOT NULL
                AND legal_hold_updated_by IS NOT NULL
            )
        );

CREATE INDEX ix_generated_documents_owner_retention
    ON generated_documents (user_id, retention_state, updated_at DESC);
CREATE INDEX ix_generated_documents_purge
    ON generated_documents (retention_state, purge_eligible_at);

CREATE TABLE document_lifecycle_events (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    document_family_id UUID NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_state VARCHAR(32) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    case_reference VARCHAR(128),
    occurred_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    retention_expires_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_document_lifecycle_events_action
        CHECK (
            action IN (
                'ARCHIVED',
                'RESTORED',
                'SOFT_DELETED',
                'LEGAL_HOLD_APPLIED',
                'LEGAL_HOLD_RELEASED',
                'PURGED'
            )
        ),
    CONSTRAINT ck_document_lifecycle_events_from_state
        CHECK (from_state IN ('AVAILABLE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_document_lifecycle_events_to_state
        CHECK (to_state IN ('AVAILABLE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_document_lifecycle_events_timestamps
        CHECK (retention_expires_at > occurred_at)
);

CREATE INDEX ix_document_lifecycle_events_document
    ON document_lifecycle_events (owner_id, document_id, occurred_at);
CREATE INDEX ix_document_lifecycle_events_retention
    ON document_lifecycle_events (retention_expires_at);

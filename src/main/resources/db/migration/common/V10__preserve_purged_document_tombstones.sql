ALTER TABLE generated_documents
    DROP CONSTRAINT ck_generated_documents_retention_state;
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_retention_state
        CHECK (retention_state IN ('AVAILABLE', 'ARCHIVED', 'DELETED', 'PURGED'));

ALTER TABLE generated_documents
    ADD COLUMN purged_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN unavailable_reason VARCHAR(64);
ALTER TABLE generated_documents
    ALTER COLUMN title DROP NOT NULL;
ALTER TABLE generated_documents
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE generated_documents
    DROP CONSTRAINT ck_generated_documents_approval_audit;
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_approval_audit CHECK (
        retention_state = 'PURGED'
        OR (lifecycle_state = 'DRAFT'
            AND approved_at IS NULL AND approved_by IS NULL)
        OR (lifecycle_state = 'APPROVED'
            AND approved_at IS NOT NULL AND approved_by IS NOT NULL)
    );

ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_purged_tombstone CHECK (
        retention_state <> 'PURGED'
        OR (
            purged_at IS NOT NULL
            AND deleted_at IS NOT NULL
            AND unavailable_reason IS NOT NULL
            AND NOT active
            AND title IS NULL
            AND content IS NULL
            AND content_sha256 IS NULL
            AND original_filename IS NULL
            AND evidence_provenance_json IS NULL
        )
    );

ALTER TABLE document_lifecycle_events
    DROP CONSTRAINT ck_document_lifecycle_events_to_state;
ALTER TABLE document_lifecycle_events
    ADD CONSTRAINT ck_document_lifecycle_events_to_state
        CHECK (to_state IN ('AVAILABLE', 'ARCHIVED', 'DELETED', 'PURGED'));

CREATE TABLE document_tombstone_associations (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    application_id UUID NOT NULL,
    association_state VARCHAR(32) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    application_status VARCHAR(32) NOT NULL,
    frozen_at TIMESTAMP(6) WITHOUT TIME ZONE,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_document_tombstone_association
        FOREIGN KEY (document_id) REFERENCES generated_documents (id),
    CONSTRAINT uq_document_tombstone_association
        UNIQUE (document_id, application_id),
    CONSTRAINT ck_document_tombstone_association_state CHECK (
        association_state IN ('DRAFT_SELECTED', 'FROZEN_USED')
    ),
    CONSTRAINT ck_document_tombstone_association_type CHECK (
        document_type IN ('CV', 'COVER_LETTER')
    )
);

CREATE INDEX ix_document_tombstone_association_application
    ON document_tombstone_associations (application_id, document_id);

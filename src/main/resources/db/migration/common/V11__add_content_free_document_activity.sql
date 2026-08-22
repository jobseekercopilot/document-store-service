CREATE TABLE document_activity_events (
    id UUID PRIMARY KEY,
    event_key VARCHAR(180) NOT NULL UNIQUE,
    owner_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    document_id UUID NOT NULL,
    document_family_id UUID NOT NULL,
    document_type VARCHAR(24) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    result VARCHAR(48) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    retention_expires_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_document_activity_event_type CHECK (event_type IN (
        'DOCUMENT_VERSION_CREATED',
        'DOCUMENT_VERSION_DOWNLOADED',
        'DOCUMENT_CURRENT_VERSION_CHANGED',
        'DOCUMENT_VERSION_ARCHIVED',
        'DOCUMENT_VERSION_RESTORED'
    )),
    CONSTRAINT chk_document_activity_document_type CHECK (
        document_type IN ('CV', 'COVER_LETTER')
    ),
    CONSTRAINT chk_document_activity_source_type CHECK (
        source_type IN ('GENERATED', 'UPLOADED')
    )
);

CREATE INDEX idx_document_activity_owner_order
    ON document_activity_events(owner_id, occurred_at, id);
CREATE INDEX idx_document_activity_retention
    ON document_activity_events(retention_expires_at);

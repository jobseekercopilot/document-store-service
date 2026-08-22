CREATE TABLE document_owner_erasure_operations (
    operation_id UUID PRIMARY KEY,
    owner_id VARCHAR(255),
    owner_fingerprint VARCHAR(64) NOT NULL,
    fingerprint_key_verifier VARCHAR(64) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    approval_reference_sha256 VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    state VARCHAR(40) NOT NULL,
    document_count INTEGER NOT NULL,
    object_scope_count INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    policy_version VARCHAR(128) NOT NULL,
    backup_retention_policy_version VARCHAR(128) NOT NULL,
    backup_retention_days INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP(6) WITHOUT TIME ZONE,
    live_data_erased_at TIMESTAMP(6) WITHOUT TIME ZONE,
    backup_retention_until TIMESTAMP(6) WITHOUT TIME ZONE,
    backup_expiry_evidence_sha256 VARCHAR(64),
    backup_expiry_attested_by VARCHAR(64),
    backup_expiry_attested_at TIMESTAMP(6) WITHOUT TIME ZONE,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT uq_document_owner_erasure_fingerprint
        UNIQUE (owner_fingerprint),
    CONSTRAINT ck_document_owner_erasure_request_sha
        CHECK (CHAR_LENGTH(request_sha256) = 64),
    CONSTRAINT ck_document_owner_erasure_owner_fingerprint
        CHECK (CHAR_LENGTH(owner_fingerprint) = 64),
    CONSTRAINT ck_document_owner_erasure_key_verifier
        CHECK (CHAR_LENGTH(fingerprint_key_verifier) = 64),
    CONSTRAINT ck_document_owner_erasure_approval_sha
        CHECK (CHAR_LENGTH(approval_reference_sha256) = 64),
    CONSTRAINT ck_document_owner_erasure_backup_evidence_sha
        CHECK (
            backup_expiry_evidence_sha256 IS NULL
            OR CHAR_LENGTH(backup_expiry_evidence_sha256) = 64
        ),
    CONSTRAINT ck_document_owner_erasure_state
        CHECK (state IN (
            'OBJECT_ERASURE_PENDING',
            'BACKUP_RETENTION_PENDING',
            'COMPLETED'
        )),
    CONSTRAINT ck_document_owner_erasure_counts
        CHECK (
            document_count >= 0
            AND object_scope_count >= 0
            AND attempt_count >= 0
            AND backup_retention_days BETWEEN 1 AND 35
        ),
    CONSTRAINT ck_document_owner_erasure_timestamps
        CHECK (
            updated_at >= created_at
            AND (last_attempt_at IS NULL OR last_attempt_at >= created_at)
            AND (
                state <> 'OBJECT_ERASURE_PENDING'
                OR (
                    owner_id IS NOT NULL
                    AND live_data_erased_at IS NULL
                    AND backup_retention_until IS NULL
                    AND backup_expiry_evidence_sha256 IS NULL
                    AND backup_expiry_attested_by IS NULL
                    AND backup_expiry_attested_at IS NULL
                    AND completed_at IS NULL
                )
            )
            AND (
                state <> 'BACKUP_RETENTION_PENDING'
                OR (
                    owner_id IS NULL
                    AND live_data_erased_at IS NOT NULL
                    AND backup_retention_until > live_data_erased_at
                    AND completed_at IS NULL
                )
            )
            AND (
                state <> 'COMPLETED'
                OR (
                    owner_id IS NULL
                    AND live_data_erased_at IS NOT NULL
                    AND backup_retention_until > live_data_erased_at
                    AND backup_expiry_evidence_sha256 IS NOT NULL
                    AND backup_expiry_attested_by IS NOT NULL
                    AND backup_expiry_attested_at >= backup_retention_until
                    AND completed_at >= backup_retention_until
                )
            )
            AND (
                (backup_expiry_evidence_sha256 IS NULL
                    AND backup_expiry_attested_by IS NULL
                    AND backup_expiry_attested_at IS NULL)
                OR
                (backup_expiry_evidence_sha256 IS NOT NULL
                    AND backup_expiry_attested_by IS NOT NULL
                    AND backup_expiry_attested_at IS NOT NULL)
            )
            AND (
                backup_expiry_attested_at IS NULL
                OR backup_expiry_attested_at >= backup_retention_until
            )
        )
);

CREATE INDEX ix_document_owner_erasure_reconciliation
    ON document_owner_erasure_operations (state, updated_at, operation_id);

CREATE TABLE document_owner_erasure_scopes (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    document_id UUID,
    storage_scope VARCHAR(512) NOT NULL,
    erased_at TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT fk_document_owner_erasure_scope_operation
        FOREIGN KEY (operation_id)
        REFERENCES document_owner_erasure_operations (operation_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_document_owner_erasure_scope
        UNIQUE (operation_id, storage_scope),
    CONSTRAINT ck_document_owner_erasure_scope_type
        CHECK (scope_type IN ('DOCUMENT_PREFIX', 'UPLOAD_KEY')),
    CONSTRAINT ck_document_owner_erasure_scope_shape
        CHECK (
            (scope_type = 'DOCUMENT_PREFIX' AND document_id IS NOT NULL)
            OR (scope_type = 'UPLOAD_KEY' AND document_id IS NULL)
        )
);

CREATE INDEX ix_document_owner_erasure_scope_pending
    ON document_owner_erasure_scopes (operation_id, erased_at, id);

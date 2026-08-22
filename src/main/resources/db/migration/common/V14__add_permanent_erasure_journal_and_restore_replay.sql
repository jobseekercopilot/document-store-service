ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE document_owner_erasure_operations
    ALTER COLUMN journal_required SET DEFAULT TRUE;

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_schema_version VARCHAR(80);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_object_key VARCHAR(512);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_object_version VARCHAR(256);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_content_sha256 VARCHAR(64);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN journal_recorded_at TIMESTAMP(6) WITHOUT TIME ZONE;

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN restore_replay_id UUID;

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN restore_replay_evidence_sha256 VARCHAR(64);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN restore_replay_requested_by VARCHAR(64);

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN restore_replay_requested_at TIMESTAMP(6) WITHOUT TIME ZONE;

ALTER TABLE document_owner_erasure_operations
    ADD COLUMN restore_replay_object_erased_at TIMESTAMP(6) WITHOUT TIME ZONE;

ALTER TABLE document_owner_erasure_operations
    DROP CONSTRAINT ck_document_owner_erasure_state;

ALTER TABLE document_owner_erasure_operations
    DROP CONSTRAINT ck_document_owner_erasure_timestamps;

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_state
        CHECK (state IN (
            'JOURNAL_PENDING',
            'OBJECT_ERASURE_PENDING',
            'RESTORE_REPLAY_PENDING',
            'BACKUP_RETENTION_PENDING',
            'COMPLETED'
        ));

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_journal_sha
        CHECK (
            journal_content_sha256 IS NULL
            OR CHAR_LENGTH(journal_content_sha256) = 64
        );

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_restore_sha
        CHECK (
            restore_replay_evidence_sha256 IS NULL
            OR CHAR_LENGTH(restore_replay_evidence_sha256) = 64
        );

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_journal_shape
        CHECK (
            (
                journal_schema_version IS NULL
                AND journal_object_key IS NULL
                AND journal_object_version IS NULL
                AND journal_content_sha256 IS NULL
                AND journal_recorded_at IS NULL
            )
            OR
            (
                journal_required = TRUE
                AND journal_schema_version IS NOT NULL
                AND journal_object_key IS NOT NULL
                AND journal_object_version IS NOT NULL
                AND journal_content_sha256 IS NOT NULL
                AND journal_recorded_at IS NOT NULL
            )
        );

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_restore_shape
        CHECK (
            (
                restore_replay_id IS NULL
                AND restore_replay_evidence_sha256 IS NULL
                AND restore_replay_requested_by IS NULL
                AND restore_replay_requested_at IS NULL
                AND restore_replay_object_erased_at IS NULL
            )
            OR
            (
                restore_replay_id IS NOT NULL
                AND restore_replay_evidence_sha256 IS NOT NULL
                AND restore_replay_requested_by IS NOT NULL
                AND restore_replay_requested_at IS NOT NULL
                AND (
                    restore_replay_object_erased_at IS NULL
                    OR restore_replay_object_erased_at >= restore_replay_requested_at
                )
            )
        );

ALTER TABLE document_owner_erasure_operations
    ADD CONSTRAINT ck_document_owner_erasure_timestamps
        CHECK (
            updated_at >= created_at
            AND (last_attempt_at IS NULL OR last_attempt_at >= created_at)
            AND (
                state <> 'JOURNAL_PENDING'
                OR (
                    journal_required = TRUE
                    AND owner_id IS NOT NULL
                    AND journal_content_sha256 IS NULL
                    AND live_data_erased_at IS NULL
                    AND backup_retention_until IS NULL
                    AND backup_expiry_evidence_sha256 IS NULL
                    AND backup_expiry_attested_by IS NULL
                    AND backup_expiry_attested_at IS NULL
                    AND completed_at IS NULL
                    AND restore_replay_id IS NULL
                )
            )
            AND (
                state <> 'OBJECT_ERASURE_PENDING'
                OR (
                    owner_id IS NOT NULL
                    AND (journal_required = FALSE OR journal_content_sha256 IS NOT NULL)
                    AND live_data_erased_at IS NULL
                    AND backup_retention_until IS NULL
                    AND backup_expiry_evidence_sha256 IS NULL
                    AND backup_expiry_attested_by IS NULL
                    AND backup_expiry_attested_at IS NULL
                    AND completed_at IS NULL
                    AND restore_replay_id IS NULL
                )
            )
            AND (
                state <> 'RESTORE_REPLAY_PENDING'
                OR (
                    journal_required = TRUE
                    AND owner_id IS NOT NULL
                    AND journal_content_sha256 IS NOT NULL
                    AND restore_replay_id IS NOT NULL
                    AND restore_replay_object_erased_at IS NULL
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
                    AND (journal_required = FALSE OR journal_content_sha256 IS NOT NULL)
                    AND live_data_erased_at IS NOT NULL
                    AND backup_retention_until > live_data_erased_at
                    AND completed_at IS NULL
                    AND (
                        restore_replay_id IS NULL
                        OR restore_replay_object_erased_at IS NOT NULL
                    )
                )
            )
            AND (
                state <> 'COMPLETED'
                OR (
                    owner_id IS NULL
                    AND (journal_required = FALSE OR journal_content_sha256 IS NOT NULL)
                    AND live_data_erased_at IS NOT NULL
                    AND backup_retention_until > live_data_erased_at
                    AND backup_expiry_evidence_sha256 IS NOT NULL
                    AND backup_expiry_attested_by IS NOT NULL
                    AND backup_expiry_attested_at >= backup_retention_until
                    AND completed_at >= backup_retention_until
                    AND (
                        restore_replay_id IS NULL
                        OR restore_replay_object_erased_at IS NOT NULL
                    )
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
        );

CREATE INDEX ix_document_owner_erasure_restore_replay
    ON document_owner_erasure_operations (restore_replay_id);

CREATE TABLE document_owner_erasure_restore_requests (
    restore_replay_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    owner_fingerprint VARCHAR(64) NOT NULL,
    fingerprint_key_verifier VARCHAR(64) NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_document_owner_erasure_restore_request_operation
        UNIQUE (operation_id),
    CONSTRAINT ck_document_owner_erasure_restore_request_owner_sha
        CHECK (CHAR_LENGTH(owner_fingerprint) = 64),
    CONSTRAINT ck_document_owner_erasure_restore_request_verifier
        CHECK (CHAR_LENGTH(fingerprint_key_verifier) = 64),
    CONSTRAINT ck_document_owner_erasure_restore_request_evidence_sha
        CHECK (CHAR_LENGTH(evidence_sha256) = 64),
    CONSTRAINT ck_document_owner_erasure_restore_request_timestamps
        CHECK (updated_at >= requested_at)
);

CREATE INDEX ix_document_owner_erasure_restore_request_reconciliation
    ON document_owner_erasure_restore_requests (updated_at, restore_replay_id);

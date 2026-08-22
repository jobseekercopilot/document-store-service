ALTER TABLE exported_document_files
    ADD COLUMN owner_id VARCHAR(255);
ALTER TABLE exported_document_files
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE exported_document_files
    ADD COLUMN storage_key VARCHAR(512);
ALTER TABLE exported_document_files
    ADD COLUMN content_size BIGINT;
ALTER TABLE exported_document_files
    ADD COLUMN content_sha256 VARCHAR(64);
ALTER TABLE exported_document_files
    ADD COLUMN storage_status VARCHAR(32) NOT NULL DEFAULT 'LEGACY_DATABASE';
ALTER TABLE exported_document_files
    ADD COLUMN stored_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE exported_document_files
    ADD COLUMN deleted_at TIMESTAMP(6) WITHOUT TIME ZONE;

UPDATE exported_document_files
SET owner_id = (
    SELECT generated_documents.user_id
    FROM generated_documents
    WHERE generated_documents.id = exported_document_files.generated_document_id
);

ALTER TABLE exported_document_files
    ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE exported_document_files
    ALTER COLUMN file_content DROP NOT NULL;

ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_version
        CHECK (version > 0);
ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_storage_status
        CHECK (storage_status IN ('LEGACY_DATABASE', 'AVAILABLE', 'DELETE_PENDING', 'UNAVAILABLE'));
ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_content_size
        CHECK (content_size IS NULL OR content_size >= 0);
ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_content_sha256
        CHECK (content_sha256 IS NULL OR CHAR_LENGTH(content_sha256) = 64);
ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_object_boundary
        CHECK (
            (
                storage_status = 'LEGACY_DATABASE'
                AND file_content IS NOT NULL
            )
            OR
            (
                storage_status <> 'LEGACY_DATABASE'
                AND file_content IS NULL
                AND storage_key IS NOT NULL
                AND content_size IS NOT NULL
                AND content_sha256 IS NOT NULL
                AND stored_at IS NOT NULL
            )
        );

CREATE UNIQUE INDEX ux_exported_document_files_storage_key
    ON exported_document_files (storage_key);
CREATE INDEX ix_exported_document_files_owner
    ON exported_document_files (owner_id);
CREATE INDEX ix_exported_document_files_storage_status
    ON exported_document_files (storage_status);

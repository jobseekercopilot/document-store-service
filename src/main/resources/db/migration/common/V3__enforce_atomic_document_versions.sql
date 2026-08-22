ALTER TABLE generated_documents
    ADD COLUMN operation_key VARCHAR(128);
ALTER TABLE generated_documents
    ADD COLUMN request_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN current_slot SMALLINT;

UPDATE generated_documents
SET current_slot = CASE WHEN active THEN 1 ELSE NULL END;

ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_operation_fingerprint
        CHECK (
            (operation_key IS NULL AND request_sha256 IS NULL)
            OR
            (
                operation_key IS NOT NULL
                AND request_sha256 IS NOT NULL
                AND CHAR_LENGTH(request_sha256) = 64
            )
        );
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_current_slot
        CHECK (
            (active AND current_slot IS NOT NULL AND current_slot = 1)
            OR
            (NOT active AND current_slot IS NULL)
        );

CREATE UNIQUE INDEX ux_generated_documents_version
    ON generated_documents (user_id, application_id, document_type, version);
CREATE UNIQUE INDEX ux_generated_documents_current
    ON generated_documents (user_id, application_id, document_type, current_slot);
CREATE UNIQUE INDEX ux_generated_documents_operation
    ON generated_documents (user_id, operation_key);

ALTER TABLE exported_document_files
    ADD COLUMN operation_key VARCHAR(128);
ALTER TABLE exported_document_files
    ADD COLUMN request_sha256 VARCHAR(64);
ALTER TABLE exported_document_files
    ADD COLUMN current_slot SMALLINT;

UPDATE exported_document_files
SET current_slot = CASE WHEN active THEN 1 ELSE NULL END;

ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_operation_fingerprint
        CHECK (
            (operation_key IS NULL AND request_sha256 IS NULL)
            OR
            (
                operation_key IS NOT NULL
                AND request_sha256 IS NOT NULL
                AND CHAR_LENGTH(request_sha256) = 64
            )
        );
ALTER TABLE exported_document_files
    ADD CONSTRAINT ck_exported_document_files_current_slot
        CHECK (
            (active AND current_slot IS NOT NULL AND current_slot = 1)
            OR
            (NOT active AND current_slot IS NULL)
        );

CREATE UNIQUE INDEX ux_exported_document_files_version
    ON exported_document_files (generated_document_id, file_type, version);
CREATE UNIQUE INDEX ux_exported_document_files_current
    ON exported_document_files (generated_document_id, file_type, current_slot);
CREATE UNIQUE INDEX ux_exported_document_files_operation
    ON exported_document_files (owner_id, operation_key);

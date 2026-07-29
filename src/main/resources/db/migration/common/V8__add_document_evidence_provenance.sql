ALTER TABLE generated_documents
    ADD COLUMN evidence_provenance_json TEXT;

ALTER TABLE generated_documents
    ADD COLUMN grounding_state VARCHAR(48);

ALTER TABLE generated_documents
    ADD COLUMN parent_document_id UUID;

ALTER TABLE generated_documents
    ADD COLUMN parent_document_version INTEGER;

UPDATE generated_documents
SET grounding_state = CASE
    WHEN source_type = 'UPLOADED'
        THEN 'USER_EDITED_REVIEW_REQUIRED'
    ELSE 'LEGACY_UNSPECIFIED'
END
WHERE grounding_state IS NULL;

ALTER TABLE generated_documents
    ALTER COLUMN grounding_state SET NOT NULL;

ALTER TABLE generated_documents
    ADD CONSTRAINT chk_generated_documents_grounding_state CHECK (
        grounding_state IN (
            'AI_GENERATED_EVIDENCE_VALIDATED',
            'USER_EDITED_REVIEW_REQUIRED',
            'USER_EDITED_REVALIDATED',
            'LEGACY_UNSPECIFIED'
        )
    );

ALTER TABLE generated_documents
    ADD CONSTRAINT chk_generated_documents_parent_version CHECK (
        (parent_document_id IS NULL AND parent_document_version IS NULL)
        OR
        (
            parent_document_id IS NOT NULL
            AND parent_document_version IS NOT NULL
            AND parent_document_version >= 1
        )
    );

CREATE INDEX idx_generated_documents_parent
    ON generated_documents (user_id, parent_document_id);

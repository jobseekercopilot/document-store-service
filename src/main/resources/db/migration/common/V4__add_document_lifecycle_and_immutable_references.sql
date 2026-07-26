ALTER TABLE generated_documents
    ADD COLUMN document_family_id UUID;
ALTER TABLE generated_documents
    ADD COLUMN lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE generated_documents
    ADD COLUMN content_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN approved_at TIMESTAMP(6) WITHOUT TIME ZONE;
ALTER TABLE generated_documents
    ADD COLUMN approved_by VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_release_id VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_bundle_id VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_bundle_version VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_bundle_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN generation_template_version VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_template_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN generation_rules_version VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_rules_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN generation_schema_id VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_schema_version VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_schema_sha256 VARCHAR(64);
ALTER TABLE generated_documents
    ADD COLUMN generation_evaluation_policy_version VARCHAR(255);
ALTER TABLE generated_documents
    ADD COLUMN generation_evaluation_policy_sha256 VARCHAR(64);

-- Pre-lifecycle records have no evidence of explicit user approval. Preserve
-- them as isolated draft families instead of treating legacy "active" as
-- approval or merging application-linked rows into a new semantic family.
UPDATE generated_documents
SET document_family_id = id,
    lifecycle_state = 'DRAFT',
    active = FALSE,
    current_slot = NULL;

ALTER TABLE generated_documents
    ALTER COLUMN document_family_id SET NOT NULL;

DROP INDEX ux_generated_documents_version;
DROP INDEX ux_generated_documents_current;

CREATE UNIQUE INDEX ux_generated_documents_family_version
    ON generated_documents (user_id, document_family_id, version);
CREATE UNIQUE INDEX ux_generated_documents_family_current
    ON generated_documents (user_id, document_family_id, current_slot);
CREATE INDEX ix_generated_documents_family_lookup
    ON generated_documents (user_id, document_family_id, version DESC);

ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_lifecycle
        CHECK (lifecycle_state IN ('DRAFT', 'APPROVED'));
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_current_is_approved
        CHECK (NOT active OR lifecycle_state = 'APPROVED');
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_approval_audit
        CHECK (
            (lifecycle_state = 'DRAFT' AND approved_at IS NULL AND approved_by IS NULL)
            OR
            (lifecycle_state = 'APPROVED' AND approved_at IS NOT NULL AND approved_by IS NOT NULL)
        );
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_content_sha256
        CHECK (content_sha256 IS NULL OR CHAR_LENGTH(content_sha256) = 64);
ALTER TABLE generated_documents
    ADD CONSTRAINT ck_generated_documents_generation_provenance
        CHECK (
            (
                generation_release_id IS NULL
                AND generation_bundle_id IS NULL
                AND generation_bundle_version IS NULL
                AND generation_bundle_sha256 IS NULL
                AND generation_template_version IS NULL
                AND generation_template_sha256 IS NULL
                AND generation_rules_version IS NULL
                AND generation_rules_sha256 IS NULL
                AND generation_schema_id IS NULL
                AND generation_schema_version IS NULL
                AND generation_schema_sha256 IS NULL
                AND generation_evaluation_policy_version IS NULL
                AND generation_evaluation_policy_sha256 IS NULL
            )
            OR
            (
                generation_release_id IS NOT NULL
                AND generation_bundle_id IS NOT NULL
                AND generation_bundle_version IS NOT NULL
                AND generation_bundle_sha256 IS NOT NULL
                AND CHAR_LENGTH(generation_bundle_sha256) = 64
                AND generation_template_version IS NOT NULL
                AND generation_template_sha256 IS NOT NULL
                AND CHAR_LENGTH(generation_template_sha256) = 64
                AND generation_rules_version IS NOT NULL
                AND generation_rules_sha256 IS NOT NULL
                AND CHAR_LENGTH(generation_rules_sha256) = 64
                AND generation_schema_id IS NOT NULL
                AND generation_schema_version IS NOT NULL
                AND generation_schema_sha256 IS NOT NULL
                AND CHAR_LENGTH(generation_schema_sha256) = 64
                AND generation_evaluation_policy_version IS NOT NULL
                AND generation_evaluation_policy_sha256 IS NOT NULL
                AND CHAR_LENGTH(generation_evaluation_policy_sha256) = 64
            )
        );

package com.jobseekercopilot.documentstore.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureScopeType;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PermanentErasureRecoveryJournalCodec {

    public static final int MAX_RECORD_BYTES = 4_194_304;
    private final ObjectMapper mapper;

    public PermanentErasureRecoveryJournalCodec(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    public Encoded encode(PermanentErasureRecoveryJournalRecord record) {
        validate(record);
        try {
            byte[] content = mapper.writeValueAsBytes(record);
            requireBounded(content);
            return new Encoded(content, sha256(content));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Permanent-erasure recovery journal could not be encoded.", exception);
        }
    }

    public PermanentErasureRecoveryJournalRecord decodeCanonical(byte[] content) {
        requireBounded(content);
        try {
            PermanentErasureRecoveryJournalRecord record = mapper.readValue(
                    content, PermanentErasureRecoveryJournalRecord.class);
            Encoded canonical = encode(record);
            if (!MessageDigest.isEqual(canonical.content(), content)) {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal is not canonical");
            }
            return record;
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal is invalid", exception);
        }
    }

    public String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validate(PermanentErasureRecoveryJournalRecord record) {
        if (record == null
                || !PermanentErasureRecoveryJournalRecord.SCHEMA_VERSION.equals(
                        record.schemaVersion())
                || record.operationId() == null
                || record.ownerId() == null
                || record.ownerId().isBlank()
                || record.ownerId().length() > 255
                || !record.ownerId().equals(record.ownerId().trim())
                || record.createdAt() == null
                || !ZoneOffset.UTC.equals(record.createdAt().getOffset())
                || !sha(record.requestSha256())
                || !sha(record.approvalReferenceSha256())
                || !bounded(record.policyVersion(), 128)
                || !bounded(record.backupRetentionPolicyVersion(), 128)
                || record.backupRetentionDays() < 1
                || record.backupRetentionDays() > 35) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal fields are invalid");
        }
        List<java.util.UUID> documentIds = record.documentIds();
        if (documentIds == null
                || documentIds.size() > 2000
                || documentIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(documentIds).size() != documentIds.size()
                || !documentIds.equals(documentIds.stream().sorted().toList())) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal document scope is invalid");
        }
        List<PermanentErasureRecoveryJournalRecord.Scope> scopes = record.objectScopes();
        if (scopes == null
                || scopes.size() > 10_000
                || scopes.stream().anyMatch(java.util.Objects::isNull)
                || !scopes.equals(scopes.stream()
                        .sorted((left, right) -> left.storageScope()
                                .compareTo(right.storageScope()))
                        .toList())) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal object scope is invalid");
        }
        Set<String> storageScopes = new HashSet<>();
        Set<java.util.UUID> documentScopes = new HashSet<>();
        for (PermanentErasureRecoveryJournalRecord.Scope scope : scopes) {
            if (scope.scopeType() == DocumentOwnerErasureScopeType.DOCUMENT_PREFIX
                    && scope.documentId() != null
                    && ("documents/" + scope.documentId() + "/")
                            .equals(scope.storageScope())) {
                documentScopes.add(scope.documentId());
            } else if (scope.scopeType() == DocumentOwnerErasureScopeType.UPLOAD_KEY
                    && scope.documentId() == null
                    && scope.storageScope() != null
                    && scope.storageScope().matches(
                            "quarantine/application-uploads/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
                // Valid exact upload scope.
            } else {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal object scope is invalid");
            }
            if (!storageScopes.add(scope.storageScope())) {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal contains duplicate object scopes");
            }
        }
        if (!documentScopes.equals(Set.copyOf(documentIds))) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal document scopes do not match");
        }
    }

    private boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean bounded(String value, int maximum) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximum
                && value.equals(value.trim());
    }

    private void requireBounded(byte[] content) {
        if (content == null || content.length < 2 || content.length > MAX_RECORD_BYTES) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal content is invalid");
        }
    }

    public record Encoded(byte[] content, String sha256) {
        public Encoded {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}

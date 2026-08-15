package com.jobseekercopilot.documentstore.storage;

import com.jobseekercopilot.documentstore.service.DocumentOwnerErasureGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LegacyDatabaseObjectMigrationService {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentObjectStorage objectStorage;
    private final DocumentOwnerErasureGuard ownerErasureGuard;

    @Transactional
    public boolean migrateNext() {
        List<LegacyObject> batch = jdbcTemplate.query("""
                SELECT id, owner_id, generated_document_id, version, mime_type,
                       file_content
                FROM exported_document_files
                WHERE storage_status = 'LEGACY_DATABASE'
                  AND file_content IS NOT NULL
                ORDER BY created_at, id
                FETCH FIRST 1 ROW ONLY
                """, this::map);
        if (batch.isEmpty()) {
            return false;
        }
        migrate(batch.get(0));
        return true;
    }

    private void migrate(LegacyObject object) {
        ownerErasureGuard.requireWritable(object.ownerId());
        String key = ObjectKeyFactory.forFile(
                object.generatedDocumentId(), object.id(), object.version());
        String sha256 = ObjectIntegrity.sha256(object.content());
        objectStorage.put(key, object.content(), object.mimeType(), sha256);
        byte[] verified = objectStorage.get(key);
        if (verified.length != object.content().length
                || !sha256.equals(ObjectIntegrity.sha256(verified))) {
            throw new ObjectStorageException(
                    "Legacy document object failed post-write integrity verification");
        }
        int updated = jdbcTemplate.update("""
                UPDATE exported_document_files
                SET storage_key = ?,
                    content_size = ?,
                    content_sha256 = ?,
                    storage_status = 'AVAILABLE',
                    stored_at = ?,
                    file_content = NULL
                WHERE id = ?
                  AND owner_id = ?
                  AND storage_status = 'LEGACY_DATABASE'
                  AND file_content IS NOT NULL
                """,
                key,
                object.content().length,
                sha256,
                LocalDateTime.now(),
                object.id(),
                object.ownerId());
        if (updated != 1) {
            String winner = jdbcTemplate.queryForObject("""
                    SELECT content_sha256
                    FROM exported_document_files
                    WHERE id = ? AND owner_id = ?
                      AND storage_status = 'AVAILABLE' AND storage_key = ?
                    """, String.class, object.id(), object.ownerId(), key);
            if (!sha256.equals(winner)) {
                throw new ObjectStorageException(
                        "Legacy document object migration lost its metadata ownership");
            }
        }
    }

    private LegacyObject map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LegacyObject(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("owner_id"),
                resultSet.getObject("generated_document_id", UUID.class),
                resultSet.getInt("version"),
                resultSet.getString("mime_type"),
                resultSet.getBytes("file_content"));
    }

    private record LegacyObject(
            UUID id,
            String ownerId,
            UUID generatedDocumentId,
            int version,
            String mimeType,
            byte[] content) {
    }
}

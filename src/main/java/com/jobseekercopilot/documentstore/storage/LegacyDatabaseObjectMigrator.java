package com.jobseekercopilot.documentstore.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class LegacyDatabaseObjectMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyDatabaseObjectMigrator.class);

    private final JdbcTemplate jdbcTemplate;
    private final DocumentObjectStorage objectStorage;

    @Override
    public void run(ApplicationArguments args) {
        int migrated = 0;
        while (true) {
            List<LegacyObject> batch = jdbcTemplate.query("""
                    SELECT id, generated_document_id, version, mime_type, file_content
                    FROM exported_document_files
                    WHERE storage_status = 'LEGACY_DATABASE' AND file_content IS NOT NULL
                    ORDER BY created_at, id
                    FETCH FIRST 1 ROW ONLY
                    """, this::map);
            if (batch.isEmpty()) {
                break;
            }
            migrate(batch.get(0));
            migrated++;
        }
        if (migrated > 0) {
            log.info("Legacy database file objects migrated count={}", migrated);
        }
    }

    private void migrate(LegacyObject object) {
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
                  AND storage_status = 'LEGACY_DATABASE'
                  AND file_content IS NOT NULL
                """,
                key,
                object.content().length,
                sha256,
                LocalDateTime.now(),
                object.id());
        if (updated != 1) {
            String winner = jdbcTemplate.queryForObject("""
                    SELECT content_sha256
                    FROM exported_document_files
                    WHERE id = ? AND storage_status = 'AVAILABLE' AND storage_key = ?
                    """, String.class, object.id(), key);
            if (!sha256.equals(winner)) {
                throw new ObjectStorageException(
                        "Legacy document object migration lost its metadata ownership");
            }
        }
    }

    private LegacyObject map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LegacyObject(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("generated_document_id", UUID.class),
                resultSet.getInt("version"),
                resultSet.getString("mime_type"),
                resultSet.getBytes("file_content"));
    }

    private record LegacyObject(
            UUID id,
            UUID generatedDocumentId,
            int version,
            String mimeType,
            byte[] content) {
    }
}

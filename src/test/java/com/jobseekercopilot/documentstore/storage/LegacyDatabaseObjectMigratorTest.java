package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacyDatabaseObjectMigratorTest {
    @TempDir
    Path objectRoot;

    @Test
    void copiesLegacyBytesBeforeClearingDatabaseBlob() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE exported_document_files (
                    id UUID PRIMARY KEY,
                    generated_document_id UUID NOT NULL,
                    version INTEGER NOT NULL,
                    mime_type VARCHAR(255) NOT NULL,
                    file_content BINARY LARGE OBJECT,
                    storage_status VARCHAR(32) NOT NULL,
                    storage_key VARCHAR(512),
                    content_size BIGINT,
                    content_sha256 VARCHAR(64),
                    stored_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        UUID documentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] content = "legacy-bytes".getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO exported_document_files (
                    id, generated_document_id, version, mime_type, file_content,
                    storage_status, created_at
                ) VALUES (?, ?, 1, 'application/pdf', ?, 'LEGACY_DATABASE', ?)
                """, fileId, documentId, content, LocalDateTime.now());
        var storage = new FileSystemDocumentObjectStorage(objectRoot);

        new LegacyDatabaseObjectMigrator(jdbc, storage)
                .run(new DefaultApplicationArguments(new String[0]));

        String key = ObjectKeyFactory.forFile(documentId, fileId, 1);
        assertThat(storage.get(key)).isEqualTo(content);
        assertThat(jdbc.queryForObject(
                "SELECT storage_status FROM exported_document_files WHERE id = ?",
                String.class,
                fileId)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT file_content FROM exported_document_files WHERE id = ?",
                byte[].class,
                fileId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT content_sha256 FROM exported_document_files WHERE id = ?",
                String.class,
                fileId)).isEqualTo(ObjectIntegrity.sha256(content));
    }
}

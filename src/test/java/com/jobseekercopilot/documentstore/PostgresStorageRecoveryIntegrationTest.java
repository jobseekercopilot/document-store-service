package com.jobseekercopilot.documentstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresStorageRecoveryIntegrationTest {
    private static final String PRIMARY_DATABASE = "document_store";
    private static final String RESTORED_DATABASE = "document_store_restore";
    private static final byte[] SYNTHETIC_FILE =
            "synthetic-document-bytes".getBytes(StandardCharsets.UTF_8);

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15.18-alpine3.23")
                    .withDatabaseName(PRIMARY_DATABASE)
                    .withUsername("document_store")
                    .withPassword("document_store");

    @Test
    void migrationRestartBackupRestoreCredentialFailureAndDeletionAreProven()
            throws Exception {
        Flyway legacyFlyway = legacyFlyway(POSTGRES.getJdbcUrl());
        assertThat(legacyFlyway.migrate().migrationsExecuted).isEqualTo(1);

        UUID documentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        try (Connection connection = primaryConnection()) {
            insertSyntheticDocument(connection, documentId);
            insertSyntheticFile(connection, documentId, fileId);
            assertStoredDocument(connection, documentId, fileId);

            assertThatThrownBy(() -> insertInvalidDocumentType(connection))
                    .isInstanceOf(SQLException.class)
                    .extracting(exception -> ((SQLException) exception).getSQLState())
                    .isEqualTo("23514");
            assertThatThrownBy(() -> insertOrphanFile(connection))
                    .isInstanceOf(SQLException.class)
                    .extracting(exception -> ((SQLException) exception).getSQLState())
                    .isEqualTo("23503");
        }

        assertThatThrownBy(() -> DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), "wrong-password"))
                .isInstanceOf(SQLException.class)
                .satisfies(exception ->
                        assertThat(((SQLException) exception).getSQLState()).startsWith("28"));

        // Apply the metadata/object separation migration to an existing BYTEA row.
        // The bytes remain recoverable as LEGACY_DATABASE until the startup
        // migrator has durably copied and verified the external object.
        Flyway flyway = flyway(POSTGRES.getJdbcUrl());
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        flyway.validate();
        try (Connection connection = primaryConnection()) {
            assertThatThrownBy(() -> markLegacyAvailableWithoutObject(connection, fileId))
                    .isInstanceOf(SQLException.class)
                    .extracting(exception -> ((SQLException) exception).getSQLState())
                    .isEqualTo("23514");
        }

        // Discard all application-side migration and JDBC state, then repeat the
        // startup path against the same durable PostgreSQL database.
        assertThat(flyway(POSTGRES.getJdbcUrl()).migrate().migrationsExecuted).isZero();
        try (Connection afterApplicationRestart = primaryConnection()) {
            assertStoredDocument(afterApplicationRestart, documentId, fileId);
        }

        assertExecSucceeded(POSTGRES.execInContainer(
                "pg_dump",
                "--username=" + POSTGRES.getUsername(),
                "--format=custom",
                "--file=/tmp/document-store.dump",
                PRIMARY_DATABASE));
        assertExecSucceeded(POSTGRES.execInContainer(
                "createdb",
                "--username=" + POSTGRES.getUsername(),
                RESTORED_DATABASE));
        assertExecSucceeded(POSTGRES.execInContainer(
                "pg_restore",
                "--username=" + POSTGRES.getUsername(),
                "--dbname=" + RESTORED_DATABASE,
                "--no-owner",
                "/tmp/document-store.dump"));

        flyway(restoredJdbcUrl()).validate();
        try (Connection restoredConnection = restoredConnection()) {
            assertStoredDocument(restoredConnection, documentId, fileId);
            deleteSyntheticDocument(restoredConnection, documentId);
            assertThat(count(
                            restoredConnection,
                            "SELECT COUNT(*) FROM exported_document_files WHERE id = ?",
                            fileId))
                    .isZero();
            assertThat(count(
                            restoredConnection,
                            "SELECT COUNT(*) FROM generated_documents WHERE id = ?",
                            documentId))
                    .isZero();
        }
    }

    private Flyway flyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common")
                .cleanDisabled(true)
                .load();
    }

    private Flyway legacyFlyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common")
                .target("1")
                .cleanDisabled(true)
                .load();
    }

    private Connection primaryConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection restoredConnection() throws SQLException {
        return DriverManager.getConnection(
                restoredJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String restoredJdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(
                        POSTGRES.getHost(),
                        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                        RESTORED_DATABASE);
    }

    private void insertSyntheticDocument(Connection connection, UUID documentId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generated_documents (
                    id, user_id, job_id, application_id, document_type, title,
                    content, version, active, source_type, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, documentId);
            statement.setString(2, "synthetic-owner");
            statement.setString(3, "synthetic-job");
            statement.setString(4, "synthetic-application");
            statement.setString(5, "CV");
            statement.setString(6, "Synthetic recovery document");
            statement.setString(7, "Synthetic CV content for storage recovery tests");
            statement.setInt(8, 1);
            statement.setBoolean(9, true);
            statement.setString(10, "GENERATED");
            statement.setString(11, "recovery-test");
            statement.setObject(12, now);
            statement.setObject(13, now);
            statement.executeUpdate();
        }
    }

    private void insertSyntheticFile(
            Connection connection, UUID documentId, UUID fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO exported_document_files (
                    id, generated_document_id, file_type, file_name, mime_type,
                    source, active, file_content, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, fileId);
            statement.setObject(2, documentId);
            statement.setString(3, "PDF");
            statement.setString(4, "synthetic.pdf");
            statement.setString(5, "application/pdf");
            statement.setString(6, "GENERATED");
            statement.setBoolean(7, true);
            statement.setBytes(8, SYNTHETIC_FILE);
            statement.setObject(9, now);
            statement.setObject(10, now);
            statement.executeUpdate();
        }
    }

    private void assertStoredDocument(
            Connection connection, UUID documentId, UUID fileId) throws Exception {
        try (PreparedStatement document = connection.prepareStatement(
                        "SELECT content FROM generated_documents WHERE id = ?");
                PreparedStatement file = connection.prepareStatement(
                        "SELECT file_content FROM exported_document_files WHERE id = ?")) {
            document.setObject(1, documentId);
            try (ResultSet result = document.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .isEqualTo("Synthetic CV content for storage recovery tests");
            }

            file.setObject(1, fileId);
            try (ResultSet result = file.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(sha256(result.getBytes(1))).isEqualTo(sha256(SYNTHETIC_FILE));
            }
        }
    }

    private void insertInvalidDocumentType(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generated_documents (
                    id, user_id, job_id, document_type, title, content, version,
                    active, source_type, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, id);
            statement.setString(2, "synthetic-owner");
            statement.setString(3, "synthetic-job");
            statement.setString(4, "UNSUPPORTED");
            statement.setString(5, "Invalid");
            statement.setString(6, "Rejected by the migration constraint");
            statement.setInt(7, 1);
            statement.setBoolean(8, true);
            statement.setString(9, "GENERATED");
            statement.setObject(10, now);
            statement.setObject(11, now);
            statement.executeUpdate();
        }
    }

    private void insertOrphanFile(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO exported_document_files (
                    id, generated_document_id, file_type, file_name, mime_type,
                    source, active, file_content, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, id);
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, "PDF");
            statement.setString(4, "orphan.pdf");
            statement.setString(5, "application/pdf");
            statement.setString(6, "GENERATED");
            statement.setBoolean(7, true);
            statement.setBytes(8, SYNTHETIC_FILE);
            statement.setObject(9, now);
            statement.setObject(10, now);
            statement.executeUpdate();
        }
    }

    private void deleteSyntheticDocument(Connection connection, UUID documentId)
            throws SQLException {
        try (PreparedStatement files = connection.prepareStatement(
                        "DELETE FROM exported_document_files WHERE generated_document_id = ?");
                PreparedStatement document = connection.prepareStatement(
                        "DELETE FROM generated_documents WHERE id = ?")) {
            files.setObject(1, documentId);
            assertThat(files.executeUpdate()).isEqualTo(1);
            document.setObject(1, documentId);
            assertThat(document.executeUpdate()).isEqualTo(1);
        }
    }

    private void markLegacyAvailableWithoutObject(Connection connection, UUID fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE exported_document_files
                SET storage_status = 'AVAILABLE'
                WHERE id = ?
                """)) {
            statement.setObject(1, fileId);
            statement.executeUpdate();
        }
    }

    private long count(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void assertExecSucceeded(Container.ExecResult result) {
        assertThat(result.getExitCode())
                .withFailMessage(
                        "Container command failed:%nstdout:%n%s%nstderr:%n%s",
                        result.getStdout(), result.getStderr())
                .isZero();
    }
}

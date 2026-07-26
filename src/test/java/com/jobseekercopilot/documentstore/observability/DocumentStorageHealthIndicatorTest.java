package com.jobseekercopilot.documentstore.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;

@ExtendWith(MockitoExtension.class)
class DocumentStorageHealthIndicatorTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DocumentObjectStorage objectStorage;

    private DocumentStorageHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator =
                new DocumentStorageHealthIndicator(dataSource, objectStorage);
    }

    @Test
    void reportsBothCurrentRequiredStoresWithoutConnectionDetails()
            throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("metadataStore", "reachable")
                .containsEntry("binaryStore", "reachable")
                .containsEntry("binaryStoreMode", "object-storage");
        verify(objectStorage).checkAvailability();
        assertThat(health.getDetails().toString())
                .doesNotContain("jdbc:", "username", "password", "exception");
    }

    @Test
    void reportsDownWithStableRedactedDetailsWhenConnectionFails()
            throws Exception {
        when(dataSource.getConnection())
                .thenThrow(new SQLException(
                        "jdbc:synthetic-secret-url user=synthetic-owner"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsOnly(
                        org.assertj.core.data.MapEntry.entry(
                                "metadataStore", "unavailable"),
                        org.assertj.core.data.MapEntry.entry(
                                "binaryStore", "unavailable"));
        assertThat(health.getDetails().toString())
                .doesNotContain(
                        "jdbc:",
                        "synthetic-secret-url",
                        "synthetic-owner",
                        "SQLException");
    }

    @Test
    void invalidConnectionIsNotReady() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenObjectStorageIsUnavailable() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        doThrow(new ObjectStorageException(
                        "https://private-bucket.example/secret-key"))
                .when(objectStorage)
                .checkAvailability();

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString())
                .doesNotContain(
                        "private-bucket",
                        "secret-key",
                        "ObjectStorageException");
    }
}

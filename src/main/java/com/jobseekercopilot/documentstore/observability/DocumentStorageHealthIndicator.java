package com.jobseekercopilot.documentstore.observability;

import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("documentStorage")
public class DocumentStorageHealthIndicator implements HealthIndicator {

    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final DocumentObjectStorage objectStorage;

    public DocumentStorageHealthIndicator(
            DataSource dataSource, DocumentObjectStorage objectStorage) {
        this.dataSource = dataSource;
        this.objectStorage = objectStorage;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                return unavailable();
            }
            objectStorage.checkAvailability();
            return Health.up()
                    .withDetail("metadataStore", "reachable")
                    .withDetail("binaryStore", "reachable")
                    .withDetail("binaryStoreMode", "object-storage")
                    .build();
        } catch (SQLException | RuntimeException exception) {
            return unavailable();
        }
    }

    private Health unavailable() {
        return Health.down()
                .withDetail("metadataStore", "unavailable")
                .withDetail("binaryStore", "unavailable")
                .build();
    }
}

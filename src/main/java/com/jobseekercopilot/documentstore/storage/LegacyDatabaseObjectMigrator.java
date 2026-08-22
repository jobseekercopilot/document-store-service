package com.jobseekercopilot.documentstore.storage;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class LegacyDatabaseObjectMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(
            LegacyDatabaseObjectMigrator.class);

    private final LegacyDatabaseObjectMigrationService migrationService;

    @Override
    public void run(ApplicationArguments args) {
        int migrated = 0;
        while (migrationService.migrateNext()) {
            migrated++;
        }
        if (migrated > 0) {
            log.info("Legacy database file objects migrated count={}", migrated);
        }
    }
}

package com.jobseekercopilot.documentstore.service;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class DocumentOperationLock {
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public void acquire(String scope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Document operation locks require a transaction");
        }
        if (isPostgres()) {
            jdbcTemplate.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (PreparedStatement statement) -> {
                        statement.setString(1, scope);
                        statement.execute();
                        return null;
                    });
            return;
        }
        acquireLocal(scope);
    }

    private boolean isPostgres() {
        return Boolean.TRUE.equals(jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection ->
                        connection.getMetaData().getDatabaseProductName()
                                .toLowerCase()
                                .contains("postgresql")));
    }

    private void acquireLocal(String scope) {
        ReentrantLock lock = localLocks.computeIfAbsent(scope, ignored -> new ReentrantLock());
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lock.unlock();
                        localLocks.compute(
                                scope,
                                (ignored, current) ->
                                        current == lock
                                                        && !lock.isLocked()
                                                        && !lock.hasQueuedThreads()
                                                ? null
                                                : current);
                    }
                });
    }
}

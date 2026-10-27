package com.library.loans.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SagaMetrics {
    private final JdbcTemplate jdbc; private final MeterRegistry registry; private final Clock clock;
    public SagaMetrics(JdbcTemplate jdbc,MeterRegistry registry,Clock clock) { this.jdbc=jdbc;this.registry=registry;this.clock=clock; }
    public void completed(UUID loanId) {
        var data=jdbc.queryForMap("select status,created_at from loans where id=?",loanId);
        String outcome=data.get("status").toString();
        Instant created=((java.sql.Timestamp)data.get("created_at")).toInstant();
        long nanos=Math.max(0,Duration.between(created,clock.instant()).toNanos());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                registry.counter("library.saga.completed","outcome",outcome).increment();
                registry.timer("library.saga.duration","outcome",outcome).record(nanos,TimeUnit.NANOSECONDS);
            }
        });
    }
}

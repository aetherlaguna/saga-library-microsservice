package com.library.catalog.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

@Component
public class OperationalMetrics {
    private final JdbcTemplate jdbc;
    public OperationalMetrics(JdbcTemplate jdbc,MeterRegistry registry) {
        this.jdbc=jdbc;
        gauge(registry,"library.outbox.pending","select count(*) from outbox_events where status='PENDING'");
        gauge(registry,"library.outbox.parked","select count(*) from outbox_events where status='PARKED'");
        gauge(registry,"library.outbox.age.seconds","select coalesce(extract(epoch from (CURRENT_TIMESTAMP-min(created_at))),0) from outbox_events where status='PENDING'");
        gauge(registry,"library.outbox.attempts","select coalesce(sum(attempts),0) from outbox_events");
        
    }
    private void gauge(MeterRegistry registry,String name,String sql) {
        Gauge.builder(name,this,metrics -> metrics.query(sql)).register(registry);
    }
    private double query(String sql) {
        try { var value=jdbc.queryForObject(sql,Double.class);return value==null?0:value; }
        catch(DataAccessException unavailable) { return Double.NaN; }
    }
}

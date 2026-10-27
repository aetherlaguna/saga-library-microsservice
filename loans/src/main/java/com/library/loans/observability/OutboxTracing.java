package com.library.loans.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

@Component
public class OutboxTracing {
    private final ObjectProvider<Tracer> tracers;
    private final ObjectProvider<Propagator> propagators;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public OutboxTracing(ObjectProvider<Tracer> tracers,ObjectProvider<Propagator> propagators,
            JdbcTemplate jdbc,JsonMapper mapper) { this.tracers=tracers;this.propagators=propagators;this.jdbc=jdbc;this.mapper=mapper; }
    public Map<String,String> capture() {
        var tracer=tracers.getIfAvailable(); var propagator=propagators.getIfAvailable();
        Map<String,String> headers=new HashMap<>();
        if(tracer!=null && propagator!=null && tracer.currentSpan()!=null)
            propagator.inject(tracer.currentSpan().context(),headers,Map::put);
        // Never persist baggage or arbitrary HTTP headers.
        headers.keySet().removeIf(key -> !key.equals("traceparent") && !key.equals("tracestate"));
        return headers;
    }
    public void publish(UUID eventId,Runnable action) {
        var tracer=tracers.getIfAvailable(); var propagator=propagators.getIfAvailable();
        if(tracer==null || propagator==null) { action.run();return; }
        String json=jdbc.queryForObject("select trace_headers::text from outbox_events where event_id=?",String.class,eventId);
        Map<String,String> headers=mapper.readValue(json,new TypeReference<Map<String,String>>() {});
        if(!headers.containsKey("traceparent")) { action.run();return; }
        var span=propagator.extract(headers,Map::get).name("outbox.publish").start();
        try(var scope=tracer.withSpan(span)) { action.run(); }
        catch(RuntimeException error) { span.error(error);throw error; }
        finally { span.end(); }
    }
}

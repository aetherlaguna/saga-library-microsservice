package com.library.catalog.messaging;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReleaseCodec {
    public record Payload(UUID requestEventId) {}
    private final JsonMapper mapper;
    public ReleaseCodec(JsonMapper mapper) { this.mapper=mapper; }
    public LibraryEvent<Payload> decode(byte[] body,String type) {
        try {
            LibraryEvent<Payload> e=mapper.readValue(body,new TypeReference<LibraryEvent<Payload>>() {});
            if(e==null || !type.equals(e.eventType()) || e.schemaVersion()!=1 || e.eventId()==null
                || e.loanId()==null || !e.loanId().equals(e.sagaId()) || !e.loanId().equals(e.correlationId())
                || e.causationId()==null || e.occurredAt()==null || e.payload()==null || e.payload().requestEventId()==null)
                throw new IllegalArgumentException("INVALID_RELEASE");
            return e;
        } catch(JacksonException error) { throw new IllegalArgumentException("UNREADABLE_RELEASE"); }
    }
    public static String fingerprint(LibraryEvent<Payload> e) {
        String canonical=e.eventId()+"\n"+e.eventType()+"\n"+e.schemaVersion()+"\n"+e.occurredAt()
            +"\n"+e.loanId()+"\n"+e.sagaId()+"\n"+e.correlationId()+"\n"+e.causationId()+"\n"+e.payload().requestEventId();
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

}

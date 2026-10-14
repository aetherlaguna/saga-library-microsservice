package com.library.catalog.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LoanRequestCodec {
    public record Request(LibraryEvent<LoanRequested> event, List<UUID> books,
            String requestHash, String eventHash) {}
    private final JsonMapper mapper;
    public LoanRequestCodec(JsonMapper mapper) { this.mapper = mapper; }

    public Request decode(byte[] body) {
        LibraryEvent<LoanRequested> event;
        try {
            event = mapper.readValue(body, new TypeReference<LibraryEvent<LoanRequested>>() {});
        } catch (JacksonException exception) {
            throw new InvalidEvent("UNREADABLE_EVENT");
        }
        if (event == null || event.eventId() == null || !"LoanRequested".equals(event.eventType())
                || event.schemaVersion() != 1 || event.occurredAt() == null || event.loanId() == null
                || event.sagaId() == null || event.correlationId() == null || event.payload() == null) {
            throw new InvalidEvent("INVALID_ENVELOPE");
        }
        var payload = event.payload();
        if (payload.memberId() == null || payload.dueAt() == null
                || !payload.dueAt().isAfter(event.occurredAt()) || payload.bookIds() == null
                || payload.bookIds().isEmpty() || payload.bookIds().contains(null)
                || new HashSet<>(payload.bookIds()).size() != payload.bookIds().size()) {
            throw new InvalidEvent("INVALID_REQUEST");
        }
        var books = payload.bookIds().stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        String canonical = payload.memberId() + "\n" + payload.dueAt() + "\n"
                + String.join("\n", books.stream().map(UUID::toString).toList());
        String eventCanonical = event.eventId() + "\n" + event.eventType() + "\n" + event.schemaVersion()
                + "\n" + event.occurredAt() + "\n" + event.loanId() + "\n" + event.sagaId()
                + "\n" + event.correlationId() + "\n" + event.causationId() + "\n" + canonical;
        return new Request(event, books, sha256(canonical), sha256(eventCanonical));
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }
}

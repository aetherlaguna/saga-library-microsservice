package com.library.loans.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CatalogResultCodec {
    public record Payload(UUID reservationId, List<UUID> bookIds, String reasonCode,
            List<UUID> unavailableBookIds) {}
    public record Result(LibraryEvent<Payload> event, boolean reserved, List<UUID> books,
            String eventHash, String outcomeHash) {}
    private final JsonMapper mapper;
    public CatalogResultCodec(JsonMapper mapper) { this.mapper = mapper; }

    public Result decode(byte[] body) {
        LibraryEvent<Payload> event;
        try {
            event = mapper.readValue(body, new TypeReference<LibraryEvent<Payload>>() {});
        } catch (JacksonException failure) {
            throw new InvalidCatalogResult("UNREADABLE_EVENT");
        }
        if (event == null || event.eventId() == null || event.schemaVersion() != 1
                || event.occurredAt() == null || event.loanId() == null || event.sagaId() == null
                || event.correlationId() == null || event.causationId() == null || event.payload() == null) {
            throw new InvalidCatalogResult("INVALID_ENVELOPE");
        }
        boolean reserved = "BooksReserved".equals(event.eventType());
        if (!reserved && !"BooksReservationRejected".equals(event.eventType())) {
            throw new InvalidCatalogResult("UNKNOWN_EVENT_TYPE");
        }
        var payload = event.payload();
        var books = reserved ? payload.bookIds() : payload.unavailableBookIds();
        if (books == null || books.isEmpty() || books.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(books).size() != books.size()
                || (reserved && payload.reservationId() == null)
                || (!reserved && !"BOOKS_UNAVAILABLE".equals(payload.reasonCode()))) {
            throw new InvalidCatalogResult("INVALID_RESULT");
        }
        books = books.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        String outcome = event.eventType() + "\n" + (reserved ? payload.reservationId() : payload.reasonCode())
                + "\n" + String.join("\n", books.stream().map(UUID::toString).toList());
        String envelope = event.eventId() + "\n" + event.schemaVersion() + "\n" + event.occurredAt()
                + "\n" + event.loanId() + "\n" + event.sagaId() + "\n" + event.correlationId()
                + "\n" + event.causationId() + "\n" + outcome;
        return new Result(event, reserved, books, hash(envelope), hash(outcome));
    }

    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

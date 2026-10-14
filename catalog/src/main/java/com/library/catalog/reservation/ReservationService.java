package com.library.catalog.reservation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.library.catalog.messaging.InvalidEvent;
import com.library.catalog.messaging.LibraryEvent;
import com.library.catalog.messaging.LoanRequestCodec;
import com.library.catalog.messaging.MessagingTopology;
import com.library.catalog.outbox.OutboxStore;

@Service
public class ReservationService {
    public static final String CONSUMER = "catalog.loan-requests.v1";
    public enum Outcome { RESERVED, REJECTED, DUPLICATE }
    public record BooksReserved(UUID reservationId, List<UUID> bookIds) {}
    public record BooksReservationRejected(String reasonCode, List<UUID> unavailableBookIds) {}
    private record Stock(UUID id, int available) {}
    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final Clock clock;
    public ReservationService(JdbcTemplate jdbc, OutboxStore outbox, Clock clock) {
        this.jdbc = jdbc; this.outbox = outbox; this.clock = clock;
    }

    @Transactional
    public Outcome reserve(LoanRequestCodec.Request request) {
        var input = request.event();
        var now = clock.instant();
        int inserted = jdbc.update("""
                INSERT INTO inbox_events(consumer_name, event_id, fingerprint, processed_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, CONSUMER, input.eventId(), request.eventHash(), Timestamp.from(now));
        if (inserted == 0) {
            String hash = jdbc.queryForObject("select fingerprint from inbox_events where consumer_name=? and event_id=?",
                    String.class, CONSUMER, input.eventId());
            if (!request.eventHash().equals(hash)) throw new InvalidEvent("EVENT_ID_REUSED");
            return Outcome.DUPLICATE;
        }

        String placeholders = String.join(",", Collections.nCopies(request.books().size(), "?"));
        // All inventory locks follow PostgreSQL's UUID order, regardless of input ordering.
        var stock = jdbc.query("select id, available_copies from books where id in (" + placeholders
                + ") order by id for update", (rs, row) -> new Stock(rs.getObject("id", UUID.class),
                        rs.getInt("available_copies")), request.books().toArray())
                .stream().collect(Collectors.toMap(Stock::id, Stock::available));
        var unavailable = request.books().stream().filter(id -> stock.getOrDefault(id, 0) < 1).toList();
        Outcome outcome = unavailable.isEmpty() ? Outcome.RESERVED : Outcome.REJECTED;
        String reason = outcome == Outcome.REJECTED ? "BOOKS_UNAVAILABLE" : null;
        UUID reservation = UUID.randomUUID();
        int won = jdbc.update("""
                INSERT INTO reservations(loan_id,reservation_id,request_hash,member_id,due_at,status,reason_code,created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (loan_id) DO NOTHING
                """, input.loanId(), reservation, request.requestHash(), input.payload().memberId(),
                Timestamp.from(input.payload().dueAt()), outcome.name(), reason, Timestamp.from(now));
        if (won == 0) {
            String hash = jdbc.queryForObject("select request_hash from reservations where loan_id=?", String.class, input.loanId());
            if (!request.requestHash().equals(hash)) throw new InvalidEvent("LOAN_ID_REUSED");
            return Outcome.DUPLICATE;
        }

        Object payload;
        String type;
        String key;
        if (outcome == Outcome.RESERVED) {
            for (UUID id : request.books()) {
                int changed = jdbc.update("update books set available_copies=available_copies-1 where id=? and available_copies>0", id);
                if (changed != 1) throw new IllegalStateException("INVENTORY_CONFLICT");
                jdbc.update("insert into reservation_books(reservation_id,book_id) values (?,?)", reservation, id);
            }
            payload = new BooksReserved(reservation, request.books());
            type = "BooksReserved";
            key = MessagingTopology.BOOKS_RESERVED;
        } else {
            // Missing titles generate a business rejection; no FK rows are written for rejected requests.
            payload = new BooksReservationRejected(reason, unavailable);
            type = "BooksReservationRejected";
            key = MessagingTopology.RESERVATION_REJECTED;
        }
        outbox.append(new LibraryEvent<>(UUID.randomUUID(), type, 1, now, input.loanId(),
                input.sagaId(), input.correlationId(), input.eventId(), payload), key);
        return outcome;
    }
}

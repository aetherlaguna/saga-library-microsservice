package com.library.catalog.reservation;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.library.catalog.messaging.LibraryEvent;
import com.library.catalog.messaging.ReleaseCodec.Payload;
import com.library.catalog.outbox.OutboxStore;

@Service
public class ReleaseService {
    private final JdbcTemplate jdbc; private final OutboxStore outbox; private final Clock clock;
    public ReleaseService(JdbcTemplate jdbc,OutboxStore outbox,Clock clock) { this.jdbc=jdbc;this.outbox=outbox;this.clock=clock; }
    @Transactional public void release(LibraryEvent<Payload> e) {
        if(!e.causationId().equals(e.payload().requestEventId())) throw new IllegalArgumentException("INVALID_CAUSATION");
        jdbc.query("select pg_advisory_xact_lock(?)",rs -> {},e.loanId().getMostSignificantBits());
        var original=jdbc.query("select causation_id from outbox_events where loan_id=? and event_type in ('BooksReserved','BooksReservationRejected')",
                (rs,i)->rs.getObject(1,UUID.class),e.loanId());
        if(!original.isEmpty() && !original.getFirst().equals(e.payload().requestEventId())) throw new IllegalArgumentException("REQUEST_MISMATCH");
        int inserted=jdbc.update("insert into release_barriers(loan_id,request_event_id,release_event_id,created_at,fingerprint) values (?,?,?,?,?) on conflict(loan_id) do nothing",
                e.loanId(),e.payload().requestEventId(),e.eventId(),Timestamp.from(clock.instant()),com.library.catalog.messaging.ReleaseCodec.fingerprint(e));
        if(inserted==0) {
            var barrier=jdbc.queryForMap("select request_event_id,release_event_id,fingerprint from release_barriers where loan_id=?",e.loanId());
            if(!com.library.catalog.messaging.ReleaseCodec.fingerprint(e).equals(barrier.get("fingerprint")) || !e.payload().requestEventId().equals(barrier.get("request_event_id")) || !e.eventId().equals(barrier.get("release_event_id")))
                throw new IllegalArgumentException("RELEASE_ID_REUSED");
            jdbc.update("update outbox_events set status='PENDING',sent_at=null,next_attempt_at=? where causation_id=? and event_type='BooksReleased' and status='SENT'",
                    Timestamp.from(clock.instant()),e.eventId());
            return;
        }
        var status=jdbc.query("select status from reservations where loan_id=? for update",(rs,i)->rs.getString(1),e.loanId());
        if(!status.isEmpty() && "RESERVED".equals(status.getFirst())) {
            var books=jdbc.query("select book_id from reservation_books where reservation_id=(select reservation_id from reservations where loan_id=?) order by book_id",
                    (rs,i)->rs.getObject(1,UUID.class),e.loanId());
            for(UUID book:books) jdbc.update("update books set available_copies=available_copies+1 where id=?",book);
            jdbc.update("update reservations set status='RELEASED' where loan_id=?",e.loanId());
        }
        outbox.append(new LibraryEvent<>(UUID.randomUUID(),"BooksReleased",1,clock.instant(),e.loanId(),e.sagaId(),e.correlationId(),e.eventId(),
                Map.of("requestEventId",e.payload().requestEventId())),"catalog.books-released.v1");
    }
}

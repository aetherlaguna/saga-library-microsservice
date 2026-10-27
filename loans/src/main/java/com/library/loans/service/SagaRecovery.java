package com.library.loans.service;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.library.loans.messaging.LibraryEvent;
import com.library.loans.outbox.OutboxStore;

@Service
public class SagaRecovery {
    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final Clock clock;
    private final Duration timeout;
    public SagaRecovery(JdbcTemplate jdbc, OutboxStore outbox, Clock clock,
            @Value("${loans.saga.timeout:5m}") Duration timeout) {
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) throw new IllegalArgumentException("Invalid saga timeout");
        this.jdbc=jdbc; this.outbox=outbox; this.clock=clock; this.timeout=timeout;
    }
    @Transactional
    public void register(UUID loanId) {
        jdbc.update("insert into loan_sagas(loan_id,state,deadline,updated_at) values (?,'WAITING',?,?)",
                loanId,Timestamp.from(clock.instant().plus(timeout)),Timestamp.from(clock.instant()));
    }
    // Call while holding the loan row lock in the caller's transaction.
    @Transactional
    public String beforeResult(UUID loanId) {
        String state=jdbc.queryForObject("select state from loan_sagas where loan_id=?",String.class,loanId);
        if ("WAITING".equals(state) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select deadline <= ? from loan_sagas where loan_id=?",Boolean.class,Timestamp.from(clock.instant()),loanId))) {
            requestRelease(loanId); return "WAITING_RELEASE";
        }
        return state;
    }
    @Transactional
    public void complete(UUID loanId) {
        jdbc.update("update loan_sagas set state='COMPLETED',updated_at=? where loan_id=?",
                Timestamp.from(clock.instant()),loanId);
    }
    @Transactional
    public int reconcile() {
        var ids=jdbc.query("""
                select l.id from loans l join loan_sagas s on s.loan_id=l.id
                where s.state='WAITING' and s.deadline<=? order by s.deadline
                limit 50 for update of l skip locked
                """,(rs,i)->rs.getObject(1,UUID.class),Timestamp.from(clock.instant()));
        for (UUID id:ids) requestRelease(id);
        // Re-publish the same release event after a lost/dead-lettered response.
        // PARKED requires operator investigation; never silently unpark protocol errors.
        jdbc.update("""
                update outbox_events o set status='PENDING',sent_at=null,next_attempt_at=?
                from loan_sagas s where s.release_event_id=o.event_id and s.state='WAITING_RELEASE'
                and o.status='SENT' and o.sent_at < ?
                """,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().minusSeconds(60)));
        return ids.size();
    }
    private void requestRelease(UUID loanId) {
        UUID request=jdbc.queryForObject("select event_id from outbox_events where loan_id=? and event_type='LoanRequested'",UUID.class,loanId);
        UUID release=UUID.randomUUID();
        outbox.append(new LibraryEvent<>(release,"BooksReleaseRequested",1,clock.instant(),loanId,loanId,loanId,request,
                Map.of("requestEventId",request)),"loans.release-requested.v1");
        jdbc.update("update loan_sagas set state='WAITING_RELEASE',release_event_id=?,updated_at=? where loan_id=?",
                release,Timestamp.from(clock.instant()),loanId);
    }
}

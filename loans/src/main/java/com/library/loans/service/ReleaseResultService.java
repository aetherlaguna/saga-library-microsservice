package com.library.loans.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.library.loans.messaging.LibraryEvent;
import com.library.loans.messaging.ReleaseCodec.Payload;
import com.library.loans.repository.LoanRepository;

@Service
public class ReleaseResultService {
    private final JdbcTemplate jdbc; private final LoanRepository loans; private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private com.library.loans.observability.SagaMetrics metrics;
    public ReleaseResultService(JdbcTemplate jdbc,LoanRepository loans,Clock clock) { this.jdbc=jdbc;this.loans=loans;this.clock=clock; }
    @Transactional public void confirm(LibraryEvent<Payload> e) {
        jdbc.query("select id from loans where id=? for update",(rs,i)->rs.getObject(1),e.loanId());
        String fingerprint=com.library.loans.messaging.ReleaseCodec.fingerprint(e);
        int inserted=jdbc.update("insert into release_result_inbox(event_id,loan_id,fingerprint,processed_at) values (?,?,?,?) on conflict(event_id) do nothing",
                e.eventId(),e.loanId(),fingerprint,Timestamp.from(clock.instant()));
        if(inserted==0) {
            String previous=jdbc.queryForObject("select fingerprint from release_result_inbox where event_id=?",String.class,e.eventId());
            if(!fingerprint.equals(previous)) throw new IllegalArgumentException("EVENT_ID_REUSED");
            return;
        }
        var saga=jdbc.query("select state,release_event_id from loan_sagas where loan_id=?",
                (rs,i)->new Object[]{rs.getString(1),rs.getObject(2,UUID.class)},e.loanId());
        if(saga.size()!=1 || !e.causationId().equals(saga.getFirst()[1])) throw new IllegalArgumentException("UNKNOWN_RELEASE");
        Integer count=jdbc.queryForObject("select count(*) from outbox_events where event_id=? and loan_id=? and event_type='LoanRequested'",
                Integer.class,e.payload().requestEventId(),e.loanId());
        if(count==null || count!=1) throw new IllegalArgumentException("UNKNOWN_REQUEST");
        if("COMPLETED".equals(saga.getFirst()[0])) return;
        if(!"WAITING_RELEASE".equals(saga.getFirst()[0])) throw new IllegalArgumentException("INVALID_SAGA_STATE");
        var loan=loans.findById(e.loanId()).orElseThrow();
        loan.cancel(); loans.flush();
        metrics.completed(e.loanId());
        jdbc.update("update loan_sagas set state='COMPLETED',updated_at=? where loan_id=?",Timestamp.from(clock.instant()),e.loanId());
    }
}

package com.library.loans.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.library.loans.domain.LoanStatus;
import com.library.loans.messaging.CatalogResultCodec.Result;
import com.library.loans.messaging.InvalidCatalogResult;
import com.library.loans.repository.LoanRepository;

@Service
public class CatalogResultService {
    public static final String CONSUMER = "loans.catalog-results.v1";
    public enum Outcome { APPLIED, DUPLICATE }
    private static final Logger log = LoggerFactory.getLogger(CatalogResultService.class);
    private final JdbcTemplate jdbc;
    private final LoanRepository loans;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public CatalogResultService(JdbcTemplate jdbc, LoanRepository loans, Clock clock,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.loans = loans; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome apply(Result result) {
        // A retry always starts a fresh transaction/persistence context and reloads the loan.
        for (int attempt = 1; ; attempt++) {
            try {
                Outcome outcome = transaction.execute(status -> applyInTransaction(result));
                log.info("Resultado de catalog processado: loanId={}, eventId={}, outcome={}",
                        result.event().loanId(), result.event().eventId(), outcome);
                return outcome;
            } catch (OptimisticLockingFailureException conflict) {
                if (attempt >= 3) throw conflict;
            }
        }
    }

    private Outcome applyInTransaction(Result result) {
        var event = result.event();
        var now = Timestamp.from(clock.instant());
        int inserted = jdbc.update("""
                INSERT INTO catalog_result_inbox(consumer_name,event_id,fingerprint,processed_at)
                VALUES (?,?,?,?) ON CONFLICT (consumer_name,event_id) DO NOTHING
                """, CONSUMER, event.eventId(), result.eventHash(), now);
        if (inserted == 0) {
            String previous = jdbc.queryForObject("select fingerprint from catalog_result_inbox where consumer_name=? and event_id=?",
                    String.class, CONSUMER, event.eventId());
            if (!result.eventHash().equals(previous)) throw new InvalidCatalogResult("EVENT_ID_REUSED");
            return Outcome.DUPLICATE;
        }
        // Validate causation against the durable original event, not untrusted AMQP headers.
        var request = jdbc.query("""
                SELECT body->>'sagaId', body->>'correlationId' FROM outbox_events
                WHERE event_id=? AND loan_id=? AND event_type='LoanRequested'
                """, (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, event.causationId(), event.loanId());
        if (request.size() != 1 || !event.sagaId().toString().equals(request.getFirst()[0])
                || !event.correlationId().toString().equals(request.getFirst()[1])) {
            throw new InvalidCatalogResult("UNKNOWN_REQUEST");
        }
        int decided = jdbc.update("""
                INSERT INTO catalog_result_decisions(loan_id,outcome_hash,event_id,created_at,event_type,reservation_id,reason_code)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (loan_id) DO NOTHING
                """, event.loanId(), result.outcomeHash(), event.eventId(), now, event.eventType(),
                result.reserved() ? event.payload().reservationId() : null,
                result.reserved() ? null : event.payload().reasonCode());
        if (decided == 0) {
            String previous = jdbc.queryForObject("select outcome_hash from catalog_result_decisions where loan_id=?",
                    String.class, event.loanId());
            if (!result.outcomeHash().equals(previous)) throw new InvalidCatalogResult("CONFLICTING_RESULT");
            return Outcome.DUPLICATE;
        }
        var loan = loans.findById(event.loanId()).orElseThrow(() -> new InvalidCatalogResult("UNKNOWN_LOAN"));
        var ids = new HashSet<>(result.books());
        if ((result.reserved() && !ids.equals(loan.getBookIds()))
                || (!result.reserved() && !loan.getBookIds().containsAll(ids))) {
            throw new InvalidCatalogResult("BOOKS_MISMATCH");
        }
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new InvalidCatalogResult("UNEXPECTED_LOAN_STATE");
        }
        if (result.reserved()) loan.activate(); else loan.cancel();
        loans.flush();
        return Outcome.APPLIED;
    }
}

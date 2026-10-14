package com.library.catalog.outbox;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore store;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxDispatcher(OutboxStore store, EventPublisher publisher,
            OutboxProperties properties, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    public enum Result { EMPTY, SENT, MESSAGE_FAILURE, INFRASTRUCTURE_FAILURE }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 45)
    public Result publishNext() {
        var next = store.lockNext(clock.instant());
        if (next.isEmpty()) {
            return Result.EMPTY;
        }
        var event = next.orElseThrow();
        try {
            publisher.publish(event);
        } catch (PublishFailure failure) {
            store.recordFailure(event, clock.instant(), failure.code(), properties);
            log.warn("Publicação sem confirmação: eventId={}, loanId={}, reason={}",
                    event.eventId(), event.loanId(), failure.code());
            return failure.code().infrastructure() ? Result.INFRASTRUCTURE_FAILURE : Result.MESSAGE_FAILURE;
        }
        store.markSent(event.eventId(), clock.instant());
        return Result.SENT;
    }

    @Transactional
    public boolean replayParked(UUID eventId) {
        return store.replayParked(eventId, clock.instant());
    }
}

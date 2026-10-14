package com.library.loans.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "library.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxDispatcher dispatcher;
    private final OutboxProperties properties;

    public OutboxScheduler(OutboxDispatcher dispatcher, OutboxProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${library.outbox.poll-interval-ms:1000}")
    public void publishBatch() {
        try {
            for (int count = 0; count < properties.batchSize(); count++) {
                var result = dispatcher.publishNext();
                if (result == OutboxDispatcher.Result.EMPTY
                        || result == OutboxDispatcher.Result.INFRASTRUCTURE_FAILURE
                        || Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            // Transaction failure leaves the event available; do not log payloads or raw error messages.
            log.error("Falha transacional no worker de outbox: type={}", failure.getClass().getSimpleName());
        }
    }
}

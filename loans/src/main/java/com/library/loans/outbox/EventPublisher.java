package com.library.loans.outbox;

@FunctionalInterface
public interface EventPublisher {
    void publish(OutboxEvent event);
}

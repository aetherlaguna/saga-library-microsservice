package com.library.catalog.outbox;

@FunctionalInterface
public interface EventPublisher {
    void publish(OutboxEvent event);
}

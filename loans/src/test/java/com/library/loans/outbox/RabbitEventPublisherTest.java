package com.library.loans.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitEventPublisherTest {
    private static class StubRabbitTemplate extends RabbitTemplate {
        Message sent;
        BiConsumer<Message, CorrelationData> response = (message, correlation) -> {};

        @Override
        public void send(String exchange, String routingKey, Message message, CorrelationData correlation) {
            sent = message;
            response.accept(message, correlation);
        }
    }

    private final StubRabbitTemplate rabbit = new StubRabbitTemplate();
    private final OutboxProperties settings = new OutboxProperties(20, 3, 10, 100, 1000);
    private final RabbitEventPublisher publisher = new RabbitEventPublisher(rabbit, settings);
    private final OutboxEvent event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(),
            "LoanRequested", 1, Instant.parse("2026-10-14T00:00:00Z"),
            "library.events", "loans.requested.v1", "{\"schemaVersion\":1}", 0, 0);

    @Test
    void waitsForAckAndSetsPersistentJsonPropertiesWithStableEventId() {
        confirm(true, null, false);
        publisher.publish(event);
        assertThat(rabbit.sent.getBody()).isEqualTo(event.body().getBytes(StandardCharsets.UTF_8));
        var properties = rabbit.sent.getMessageProperties();
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(properties.getCorrelationId()).isEqualTo(event.loanId().toString());
        assertThat(properties.getAppId()).isEqualTo("loans");
    }

    private void confirm(boolean ack, String reason, boolean returned) {
        rabbit.response = (message, correlation) -> {
            if (returned) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "library.events", "missing"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
        };
    }

    private void failsWith(PublishFailure.Code code) {
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOfSatisfying(PublishFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @Test
    void ackWithReturnIsNotSuccess() {
        confirm(true, null, true);
        failsWith(PublishFailure.Code.UNROUTABLE);
    }

    @Test
    void brokerNackIsNotSuccess() {
        confirm(false, null, false);
        failsWith(PublishFailure.Code.NACK);
    }

    @Test
    void syntheticNackIsInfrastructureFailure() {
        confirm(false, "channel closed", false);
        failsWith(PublishFailure.Code.CONFIRM_FAILED);
    }

    @Test
    void confirmTimeoutIsNotSuccess() {
        failsWith(PublishFailure.Code.CONFIRM_TIMEOUT);
    }

    @Test
    void failedFutureIsInfrastructureFailure() {
        rabbit.response = (message, correlation) -> correlation.getFuture().completeExceptionally(new IllegalStateException());
        failsWith(PublishFailure.Code.CONFIRM_FAILED);
    }

    @Test
    void interruptionPreservesThreadFlag() {
        Thread.currentThread().interrupt();
        try {
            failsWith(PublishFailure.Code.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void connectionFailureUsesSafeCode() {
        rabbit.response = (message, correlation) -> {
            throw new AmqpConnectException(new java.net.ConnectException("sensitive-host"));
        };
        failsWith(PublishFailure.Code.BROKER_UNAVAILABLE);
    }

    @Test
    void backoffGrowsAndStaysCappedForLongOutages() {
        assertThat(settings.retryDelay(1)).isEqualTo(100);
        assertThat(settings.retryDelay(2)).isEqualTo(200);
        assertThat(settings.retryDelay(4)).isEqualTo(800);
        assertThat(settings.retryDelay(5)).isEqualTo(1000);
        assertThat(settings.retryDelay(Long.MAX_VALUE)).isEqualTo(1000);
    }
}

package com.library.loans.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitEventPublisher implements EventPublisher {
    private final RabbitTemplate rabbit;
    private final OutboxProperties properties;

    public RabbitEventPublisher(RabbitTemplate rabbit, OutboxProperties properties) {
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        var messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(event.eventId().toString());
        messageProperties.setType(event.eventType());
        messageProperties.setAppId("loans");
        messageProperties.setTimestamp(Date.from(event.createdAt()));
        messageProperties.setCorrelationId(event.loanId().toString());
        messageProperties.setHeader("schemaVersion", event.schemaVersion());
        messageProperties.setHeader("sagaId", event.loanId().toString());
        var message = new Message(event.body().getBytes(StandardCharsets.UTF_8), messageProperties);
        // A publish attempt has its own confirm id; the business event id never changes.
        var correlation = new CorrelationData();
        try {
            rabbit.send(event.exchangeName(), event.routingKey(), message, correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                throw new PublishFailure(PublishFailure.Code.UNROUTABLE);
            }
            if (!confirm.isAck()) {
                // AMQP basic.nack has no reason field; a reason indicates a local/framework failure.
                throw new PublishFailure(confirm.getReason() == null
                        ? PublishFailure.Code.NACK : PublishFailure.Code.CONFIRM_FAILED);
            }
        } catch (AmqpException exception) {
            throw new PublishFailure(PublishFailure.Code.BROKER_UNAVAILABLE);
        } catch (TimeoutException exception) {
            throw new PublishFailure(PublishFailure.Code.CONFIRM_TIMEOUT);
        } catch (ExecutionException exception) {
            throw new PublishFailure(PublishFailure.Code.CONFIRM_FAILED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublishFailure(PublishFailure.Code.INTERRUPTED);
        }
    }
}

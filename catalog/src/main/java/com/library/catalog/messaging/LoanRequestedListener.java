package com.library.catalog.messaging;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import com.library.catalog.reservation.ReservationService;

@Component
@EnableConfigurationProperties(ConsumerProperties.class)
public class LoanRequestedListener {
    public static final String ID = "catalog-loan-requests";
    private static final Logger log = LoggerFactory.getLogger(LoanRequestedListener.class);
    private final LoanRequestCodec codec;
    private final ReservationService reservations;
    private final ConsumerProperties properties;
    public LoanRequestedListener(LoanRequestCodec codec, ReservationService reservations, ConsumerProperties properties) {
        this.codec = codec; this.reservations = reservations; this.properties = properties;
    }

    @RabbitListener(id = ID, queues = MessagingTopology.CATALOG_REQUESTS, ackMode = "MANUAL",
            autoStartup = "${catalog.consumer.enabled:true}", concurrency = "${catalog.consumer.concurrency:2}")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            // This call returns through a separate transactional proxy only after commit.
            reservations.reserve(codec.decode(message.getBody()));
        } catch (InvalidEvent failure) {
            log.warn("Evento rejeitado pelo catálogo: reason={}", failure.getMessage());
            channel.basicReject(tag, false);
            return;
        } catch (RuntimeException failure) {
            log.warn("Falha de processamento do catálogo: type={}", failure.getClass().getSimpleName());
            try {
                Thread.sleep(properties.retryBackoffMs());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // Bounded by quorum delivery-limit; avoid hot requeue loops while DB is unavailable.
            channel.basicReject(tag, true);
            return;
        }
        channel.basicAck(tag, false);
    }
}

package com.library.loans.messaging;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import com.library.loans.service.CatalogResultService;

@Component
public class CatalogResultListener {
    public static final String ID = "loans-catalog-results";
    private static final Logger log = LoggerFactory.getLogger(CatalogResultListener.class);
    private final CatalogResultCodec codec;
    private final CatalogResultService results;
    private final long backoff;
    public CatalogResultListener(CatalogResultCodec codec, CatalogResultService results,
            @Value("${loans.consumer.retry-backoff-ms:1000}") long backoff) {
        if (backoff < 100 || backoff > 10000) throw new IllegalArgumentException("Invalid consumer backoff");
        this.codec = codec; this.results = results; this.backoff = backoff;
    }

    @RabbitListener(id = ID, queues = MessagingTopology.LOAN_RESULTS, ackMode = "MANUAL",
            autoStartup = "${loans.consumer.enabled:true}", concurrency = "${loans.consumer.concurrency:2}")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            var result = codec.decode(message.getBody());
            String expected = result.reserved() ? MessagingTopology.BOOKS_RESERVED : MessagingTopology.RESERVATION_REJECTED;
            if (!expected.equals(message.getMessageProperties().getReceivedRoutingKey())) {
                throw new InvalidCatalogResult("ROUTING_KEY_MISMATCH");
            }
            // apply() returns only after the transaction has committed.
            results.apply(result);
        } catch (InvalidCatalogResult failure) {
            log.warn("Resultado de catalog rejeitado: reason={}", failure.getMessage());
            channel.basicReject(tag, false);
            return;
        } catch (RuntimeException failure) {
            log.warn("Falha ao processar resultado de catalog: type={}", failure.getClass().getSimpleName());
            try { Thread.sleep(backoff); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicReject(tag, true);
            return;
        }
        channel.basicAck(tag, false);
    }
}

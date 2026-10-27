package com.library.loans.messaging;

import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import com.library.loans.service.ReleaseResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReleaseListener {
    private static final Logger log=LoggerFactory.getLogger(ReleaseListener.class);
    private final ReleaseCodec codec; private final ReleaseResultService service;
    public ReleaseListener(ReleaseCodec codec,ReleaseResultService service) { this.codec=codec;this.service=service; }
    @RabbitListener(id="loans-releases",queues=ReleaseTopology.RESULTS,ackMode="MANUAL",
        autoStartup="${loans.consumer.enabled:true}",concurrency="1")
    public void receive(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        try {
            if(!"catalog.books-released.v1".equals(message.getMessageProperties().getReceivedRoutingKey())) throw new IllegalArgumentException("WRONG_ROUTE");
            service.confirm(codec.decode(message.getBody(),"BooksReleased"));
        } catch(IllegalArgumentException invalid) { log.warn("Release rejeitado: reason={}",invalid.getMessage());channel.basicReject(tag,false);return; }
        catch(RuntimeException failure) {
            log.warn("Falha de release: type={}",failure.getClass().getSimpleName());
            try { Thread.sleep(1000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicReject(tag,true);return;
        }
        channel.basicAck(tag,false);
    }
}

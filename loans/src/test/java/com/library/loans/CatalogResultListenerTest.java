package com.library.loans;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import com.rabbitmq.client.Channel;
import com.library.loans.messaging.*;
import com.library.loans.service.CatalogResultService;

class CatalogResultListenerTest {
    @Test void confirmsOnlyAfterServiceReturnsAndRejectsTransientAndPermanentFailures() throws Exception {
        var codec=mock(CatalogResultCodec.class);
        var service=mock(CatalogResultService.class);
        var channel=mock(Channel.class);
        var result=mock(CatalogResultCodec.Result.class);
        var listener=new CatalogResultListener(codec,service,100);
        var props=new MessageProperties(); props.setDeliveryTag(7); props.setReceivedRoutingKey(MessagingTopology.BOOKS_RESERVED);
        byte[] body={1}; var message=new Message(body,props);
        when(codec.decode(body)).thenReturn(result); when(result.reserved()).thenReturn(true);
        var event=mock(LibraryEvent.class); when(result.event()).thenReturn(event);
        when(event.loanId()).thenReturn(java.util.UUID.randomUUID());
        when(event.sagaId()).thenReturn(java.util.UUID.randomUUID());
        when(event.eventId()).thenReturn(java.util.UUID.randomUUID());
        listener.receive(message,channel);
        var ordered=inOrder(service,channel); ordered.verify(service).apply(result); ordered.verify(channel).basicAck(7,false);
        reset(channel);
        doThrow(new IllegalStateException("DB unavailable")).when(service).apply(result);
        listener.receive(message,channel); verify(channel).basicReject(7,true); verify(channel,never()).basicAck(anyLong(),anyBoolean());
        reset(channel);
        when(codec.decode(body)).thenThrow(new InvalidCatalogResult("INVALID_RESULT"));
        listener.receive(message,channel); verify(channel).basicReject(7,false);
    }
}

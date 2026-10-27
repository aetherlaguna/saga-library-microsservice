package com.library.catalog.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class ReleaseTopology {
    public static final String REQUESTS="catalog.release-requests.v1";
    public static final String RESULTS="loans.release-results.v1";
    @Bean Declarables releaseDeclarations() {
        var exchange=new TopicExchange(MessagingTopology.EVENTS,true,false);
        var dlx=new TopicExchange(MessagingTopology.DEAD_LETTERS,true,false);
        var requests=queue(REQUESTS); var results=queue(RESULTS);
        var requestDlq=QueueBuilder.durable(REQUESTS+".dlq").quorum().build();
        var resultDlq=QueueBuilder.durable(RESULTS+".dlq").quorum().build();
        return new Declarables(exchange,dlx,requests,results,requestDlq,resultDlq,
            BindingBuilder.bind(requests).to(exchange).with("loans.release-requested.v1"),
            BindingBuilder.bind(results).to(exchange).with("catalog.books-released.v1"),
            BindingBuilder.bind(requestDlq).to(dlx).with(REQUESTS+".dead"),
            BindingBuilder.bind(resultDlq).to(dlx).with(RESULTS+".dead"));
    }
    private Queue queue(String name) {
        return QueueBuilder.durable(name).quorum().deadLetterExchange(MessagingTopology.DEAD_LETTERS)
            .deadLetterRoutingKey(name+".dead").withArgument("x-delivery-limit",3)
            .withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").build();
    }
}

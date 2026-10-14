package com.library.catalog.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MessagingTopology {
    public static final String EVENTS = "library.events";
    public static final String LOAN_REQUESTED = "loans.requested.v1";
    public static final String CATALOG_REQUESTS = "catalog.loan-requests.v1";
    public static final String DEAD_LETTERS = "library.events.dlx";
    public static final String CATALOG_DLQ = "catalog.loan-requests.v1.dlq";
    public static final String CATALOG_DEAD_KEY = "catalog.loan-requests.v1.dead";

    public static final String BOOKS_RESERVED = "catalog.books-reserved.v1";
    public static final String RESERVATION_REJECTED = "catalog.reservation-rejected.v1";
    public static final String LOAN_RESULTS = "loans.catalog-results.v1";
    public static final String LOAN_RESULTS_DLQ = "loans.catalog-results.v1.dlq";
    public static final String LOAN_RESULTS_DEAD_KEY = "loans.catalog-results.v1.dead";

    @Bean
    Declarables loanEventsTopology() {
        var events = new TopicExchange(EVENTS, true, false);
        var deadLetters = new TopicExchange(DEAD_LETTERS, true, false);
        var requests = QueueBuilder.durable(CATALOG_REQUESTS).quorum()
                .deadLetterExchange(DEAD_LETTERS).deadLetterRoutingKey(CATALOG_DEAD_KEY)
                .withArgument("x-delivery-limit", 3)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish").build();
        var dlq = QueueBuilder.durable(CATALOG_DLQ).quorum().build();
        var results = QueueBuilder.durable(LOAN_RESULTS).quorum()
                .deadLetterExchange(DEAD_LETTERS).deadLetterRoutingKey(LOAN_RESULTS_DEAD_KEY)
                .withArgument("x-delivery-limit", 3)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish").build();
        var resultsDlq = QueueBuilder.durable(LOAN_RESULTS_DLQ).quorum().build();
        return new Declarables(events, deadLetters, requests, dlq, results, resultsDlq,
                BindingBuilder.bind(results).to(events).with(BOOKS_RESERVED),
                BindingBuilder.bind(results).to(events).with(RESERVATION_REJECTED),
                BindingBuilder.bind(resultsDlq).to(deadLetters).with(LOAN_RESULTS_DEAD_KEY),
                BindingBuilder.bind(requests).to(events).with(LOAN_REQUESTED),
                BindingBuilder.bind(dlq).to(deadLetters).with(CATALOG_DEAD_KEY));
    }
}

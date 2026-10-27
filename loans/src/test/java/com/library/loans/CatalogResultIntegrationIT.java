package com.library.loans;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.library.loans.messaging.*;
import com.library.loans.service.*;

@SpringBootTest(properties = {"loans.saga.recovery-enabled=false", "library.outbox.enabled=false", "loans.consumer.enabled=false",
        "loans.consumer.retry-backoff-ms=100"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CatalogResultIntegrationIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("spring.rabbitmq.host", RABBIT::getHost);
        p.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        p.add("spring.rabbitmq.username", () -> "test");
        p.add("spring.rabbitmq.password", () -> "test");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanService loans;
    @Autowired CatalogResultService results;
    @Autowired CatalogResultCodec codec;
    @Autowired SagaRecovery recovery;
    @Autowired ReleaseResultService releaseResults;
    @Autowired CatalogResultListener resultListener;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry registry;
    UUID loan; UUID book; UUID request;

    @BeforeEach void setup() {
        registry.getListenerContainer(CatalogResultListener.ID).stop();
        jdbc.update("delete from catalog_result_inbox");
        jdbc.update("delete from catalog_result_decisions");
        jdbc.update("delete from outbox_events");
        jdbc.update("delete from loan_books");
        jdbc.update("delete from loans");
        admin.initialize();
        admin.purgeQueue(MessagingTopology.LOAN_RESULTS, false);
        admin.purgeQueue(MessagingTopology.LOAN_RESULTS_DLQ, false);
        book = UUID.randomUUID();
        loan = loans.create(UUID.randomUUID(), UUID.randomUUID(), Set.of(book), Instant.now().plusSeconds(86400)).id();
        request = jdbc.queryForObject("select event_id from outbox_events where loan_id=?", UUID.class, loan);
    }
    String body(boolean reserved, UUID eventId) {
        String payload = reserved ? "{\"reservationId\":\"00000000-0000-0000-0000-000000000001\",\"bookIds\":[\""+book+"\"]}"
                : "{\"reasonCode\":\"BOOKS_UNAVAILABLE\",\"unavailableBookIds\":[\""+book+"\"]}";
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-10-26T10:00:00Z",
                "loanId":"%s","sagaId":"%s","correlationId":"%s","causationId":"%s","payload":%s}
                """.formatted(eventId,reserved?"BooksReserved":"BooksReservationRejected",loan,loan,loan,request,payload);
    }
    CatalogResultCodec.Result decode(String body) { return codec.decode(body.getBytes(StandardCharsets.UTF_8)); }
    String status() { return jdbc.queryForObject("select status from loans where id=?", String.class,loan); }
    long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }

    @Test void activatesAndDeduplicatesAcrossServiceInstancesAndNewEventIds() {
        var result = decode(body(true,UUID.randomUUID()));
        assertThat(results.apply(result)).isEqualTo(CatalogResultService.Outcome.APPLIED);
        assertThat(results.apply(result)).isEqualTo(CatalogResultService.Outcome.DUPLICATE);
        assertThat(results.apply(decode(body(true,UUID.randomUUID())))).isEqualTo(CatalogResultService.Outcome.DUPLICATE);
        assertThat(status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select version from loans where id=?", Long.class,loan)).isEqualTo(1);
        assertThat(count("catalog_result_inbox")).isEqualTo(2);
        assertThat(count("catalog_result_decisions")).isEqualTo(1);
    }
    @Test void cancelsOnRejection() {
        results.apply(decode(body(false,UUID.randomUUID())));
        assertThat(status()).isEqualTo("CANCELLED");
    }
    @Test void rejectsReusedEventIdAndContradictoryOutcomeWithoutLeavingInbox() {
        UUID id=UUID.randomUUID();
        results.apply(decode(body(true,id)));
        assertThatThrownBy(() -> results.apply(decode(body(false,id)))).hasMessage("EVENT_ID_REUSED");
        assertThatThrownBy(() -> results.apply(decode(body(false,UUID.randomUUID())))).hasMessage("CONFLICTING_RESULT");
        assertThat(count("catalog_result_inbox")).isEqualTo(1);
        assertThat(status()).isEqualTo("ACTIVE");
    }
    @Test void validatesCausationAndBookSetAtomically() {
        String valid=body(true,UUID.randomUUID());
        assertThatThrownBy(() -> results.apply(decode(valid.replace(request.toString(), UUID.randomUUID().toString()))))
                .hasMessage("UNKNOWN_REQUEST");
        assertThatThrownBy(() -> results.apply(decode(valid.replace(book.toString(), UUID.randomUUID().toString()))))
                .hasMessage("BOOKS_MISMATCH");
        assertThat(count("catalog_result_inbox")).isZero();
        assertThat(count("catalog_result_decisions")).isZero();
        assertThat(status()).isEqualTo("PENDING");
    }
    @Test void commitFailureRollsBackStatusInboxAndDecisionAndCanBeRetried() {
        var result=decode(body(true,UUID.randomUUID()));
        jdbc.execute("alter table loans add constraint test_reject_active check(status <> 'ACTIVE') not valid");
        try {
            assertThatThrownBy(() -> results.apply(result)).isInstanceOf(RuntimeException.class);
            assertThat(status()).isEqualTo("PENDING");
            assertThat(count("catalog_result_inbox")).isZero();
            assertThat(count("catalog_result_decisions")).isZero();
        } finally { jdbc.execute("alter table loans drop constraint test_reject_active"); }
        assertThat(results.apply(result)).isEqualTo(CatalogResultService.Outcome.APPLIED);
    }
    @Test void concurrentDuplicateDoesNotApplyTwice() throws Exception {
        var event=decode(body(true,UUID.randomUUID()));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> results.apply(event));
            var b=pool.submit(() -> results.apply(event));
            assertThat(Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(CatalogResultService.Outcome.APPLIED,CatalogResultService.Outcome.DUPLICATE);
        }
        assertThat(count("catalog_result_decisions")).isEqualTo(1);
    }
    @Test void consumesPersistentResultAfterListenerRestartAndDeadLettersInvalidBody() {
        var props=new MessageProperties(); props.setContentType("application/json");
        rabbit.send(MessagingTopology.EVENTS,MessagingTopology.BOOKS_RESERVED,
                new Message(body(true,UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),props));
        assertThat(status()).isEqualTo("PENDING");
        var listener=registry.getListenerContainer(CatalogResultListener.ID);
        listener.start();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(status()).isEqualTo("ACTIVE"));
        listener.stop(); listener.start();
        rabbit.send(MessagingTopology.EVENTS,MessagingTopology.BOOKS_RESERVED,new Message("{".getBytes(StandardCharsets.UTF_8),props));
        assertThat(rabbit.receive(MessagingTopology.LOAN_RESULTS_DLQ,15000)).isNotNull();
        assertThat(count("catalog_result_inbox")).isEqualTo(1);
    }
    @Test void ackOccursAfterCommitAndLostAckDoesNotRepeatEffect() throws Exception {
        var channel=org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class);
        var props=new MessageProperties(); props.setDeliveryTag(9);
        props.setReceivedRoutingKey(MessagingTopology.BOOKS_RESERVED);
        var message=new Message(body(true,UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),props);
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(status()).isEqualTo("ACTIVE");
            assertThat(count("catalog_result_inbox")).isEqualTo(1);
            throw new java.io.IOException("lost ack");
        }).when(channel).basicAck(9,false);
        assertThatThrownBy(() -> resultListener.receive(message,channel)).isInstanceOf(java.io.IOException.class);
        org.mockito.Mockito.reset(channel);
        resultListener.receive(message,channel);
        org.mockito.Mockito.verify(channel).basicAck(9,false);
        assertThat(jdbc.queryForObject("select version from loans where id=?",Long.class,loan)).isEqualTo(1);
    }

    @Test void persistentDatabaseFailureIsRetriedBoundedlyThenDeadLettered() {
        jdbc.execute("alter table loans add constraint test_reject_active check(status <> 'ACTIVE') not valid");
        try {
            var props=new MessageProperties(); props.setContentType("application/json");
            rabbit.send(MessagingTopology.EVENTS,MessagingTopology.BOOKS_RESERVED,
                    new Message(body(true,UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),props));
            registry.getListenerContainer(CatalogResultListener.ID).start();
            assertThat(rabbit.receive(MessagingTopology.LOAN_RESULTS_DLQ,20000)).isNotNull();
            assertThat(status()).isEqualTo("PENDING");
            assertThat(count("catalog_result_inbox")).isZero();
        } finally {
            registry.getListenerContainer(CatalogResultListener.ID).stop();
            jdbc.execute("alter table loans drop constraint test_reject_active");
        }
    }

    @Test void timeoutWaitsForReleaseAndLateSuccessCannotActivateLoan() {
        jdbc.update("update loan_sagas set deadline=? where loan_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),loan);
        assertThat(recovery.reconcile()).isEqualTo(1);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select state from loan_sagas where loan_id=?",String.class,loan)).isEqualTo("WAITING_RELEASE");
        results.apply(decode(body(true,UUID.randomUUID())));
        assertThat(status()).isEqualTo("PENDING");
        UUID release=jdbc.queryForObject("select release_event_id from loan_sagas where loan_id=?",UUID.class,loan);
        var confirm=new LibraryEvent<>(UUID.randomUUID(),"BooksReleased",1,Instant.now(),loan,loan,loan,release,new ReleaseCodec.Payload(request));
        releaseResults.confirm(confirm); releaseResults.confirm(confirm);
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select state from loan_sagas where loan_id=?",String.class,loan)).isEqualTo("COMPLETED");
    }
    @Test void lateResultAfterReleaseConfirmationKeepsCancelledState() {
        jdbc.update("update loan_sagas set deadline=? where loan_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),loan);
        recovery.reconcile();
        UUID release=jdbc.queryForObject("select release_event_id from loan_sagas where loan_id=?",UUID.class,loan);
        releaseResults.confirm(new LibraryEvent<>(UUID.randomUUID(),"BooksReleased",1,Instant.now(),loan,loan,loan,release,new ReleaseCodec.Payload(request)));
        results.apply(decode(body(true,UUID.randomUUID())));
        assertThat(status()).isEqualTo("CANCELLED");
    }
    @Test void releaseReplayPreservesEventIdentityAndRecoveryIsRestartSafe() {
        jdbc.update("update loan_sagas set deadline=? where loan_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),loan);
        recovery.reconcile(); recovery.reconcile();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='BooksReleaseRequested'",Long.class)).isEqualTo(1);
        UUID release=jdbc.queryForObject("select release_event_id from loan_sagas where loan_id=?",UUID.class,loan);
        jdbc.update("update outbox_events set status='SENT',sent_at=?,next_attempt_at=? where event_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)),java.sql.Timestamp.from(Instant.now()),release);
        recovery.reconcile();
        assertThat(jdbc.queryForObject("select status from outbox_events where event_id=?",String.class,release)).isEqualTo("PENDING");
    }

}

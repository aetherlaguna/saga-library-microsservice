package com.library.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;
import com.library.loans.messaging.MessagingTopology;
import com.library.loans.outbox.OutboxDispatcher;
import com.library.loans.service.LoanService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loans.consumer.enabled=false", "library.outbox.enabled=false", "library.outbox.retry-delay-ms=10",
        "library.outbox.max-retry-delay-ms=50", "library.outbox.max-message-failures=3",
        "library.outbox.confirm-timeout-ms=1000"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OutboxIntegrationIT {
    private static final String USER = "test_" + UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> USER);
        properties.add("spring.rabbitmq.password", () -> PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired LoanService loans;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Value("${local.server.port}") int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanDisposableInfrastructure() {
        jdbc.update("delete from outbox_events");
        jdbc.update("delete from loan_books");
        jdbc.update("delete from loans");
        admin.initialize();
        admin.purgeQueue(MessagingTopology.CATALOG_REQUESTS, false);
        admin.purgeQueue(MessagingTopology.CATALOG_DLQ, false);
    }

    private UUID createLoan() {
        return loans.create(UUID.randomUUID(), UUID.randomUUID(),
                Set.of(UUID.randomUUID(), UUID.randomUUID()), Instant.now().plusSeconds(86400)).id();
    }

    private UUID eventId(UUID loanId) {
        return jdbc.queryForObject("select event_id from outbox_events where loan_id = ?", UUID.class, loanId);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("select status from outbox_events where event_id = ?", String.class, eventId);
    }

    private void makeDue(UUID eventId) {
        jdbc.update("update outbox_events set next_attempt_at = '2000-01-01T00:00:00Z' where event_id = ?", eventId);
    }

    private Message receive(String queue) {
        Message message = rabbit.receive(queue, 10000);
        assertThat(message).isNotNull();
        return message;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createsVersionedEventAtomicallyAndPublishesPersistentJsonOnlyOnceAfterConfirm() {
        UUID loan = createLoan();
        UUID event = eventId(loan);
        assertThat(status(event)).isEqualTo("PENDING");
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        assertThat(status(event)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select sent_at is not null from outbox_events where event_id = ?", Boolean.class, event)).isTrue();
        var message = receive(MessagingTopology.CATALOG_REQUESTS);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        var json = JsonPath.parse(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(json.read("$.eventId", String.class)).isEqualTo(event.toString());
        assertThat(json.read("$.eventType", String.class)).isEqualTo("LoanRequested");
        assertThat(json.read("$.schemaVersion", Integer.class)).isEqualTo(1);
        assertThat(json.read("$.loanId", String.class)).isEqualTo(loan.toString());
        assertThat(json.read("$.sagaId", String.class)).isEqualTo(loan.toString());
        assertThat(json.read("$.correlationId", String.class)).isEqualTo(loan.toString());
        assertThat(json.read("$.causationId", Object.class)).isNull();
        assertThat(Instant.parse(json.read("$.occurredAt", String.class))).isBefore(Instant.now().plusSeconds(1));
        assertThat(json.read("$.payload.bookIds.length()", Integer.class)).isEqualTo(2);
        assertThat(message.getBody()).asString(StandardCharsets.UTF_8).doesNotContain("registeredBy", PASSWORD);
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.EMPTY);
        assertThat(rabbit.receive(MessagingTopology.CATALOG_REQUESTS, 100)).isNull();
    }

    @Test
    void failureToInsertOutboxRollsBackLoanAndBooks() {
        jdbc.execute("alter table outbox_events add constraint reject_test_event check (event_type <> 'LoanRequested') not valid");
        try {
            assertThatThrownBy(this::createLoan).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from loan_books", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isZero();
        } finally {
            jdbc.execute("alter table outbox_events drop constraint reject_test_event");
        }
    }

    @Test
    void unroutableMessageIsRetriedThenParkedAndExplicitReplayPreservesEventId() {
        UUID event = eventId(createLoan());
        jdbc.update("update outbox_events set routing_key = 'unbound.test.v1' where event_id = ?", event);
        for (int attempt = 0; attempt < 3; attempt++) {
            makeDue(event);
            assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.MESSAGE_FAILURE);
            assertThat(status(event)).isEqualTo(attempt < 2 ? "PENDING" : "PARKED");
            assertThat(jdbc.queryForObject("select sent_at from outbox_events where event_id = ?", java.sql.Timestamp.class, event)).isNull();
        }
        assertThat(jdbc.queryForObject("select last_error from outbox_events where event_id = ?", String.class, event)).isEqualTo("UNROUTABLE");
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.EMPTY);
        jdbc.update("update outbox_events set routing_key = ? where event_id = ?", MessagingTopology.LOAN_REQUESTED, event);
        assertThat(dispatcher.replayParked(event)).isTrue();
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        assertThat(receive(MessagingTopology.CATALOG_REQUESTS).getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(dispatcher.replayParked(event)).isFalse();
    }

    @Test
    void brokerOutageDoesNotPreventHttpCreationOrReadinessAndRecoversInFreshContext() throws Exception {
        UUID event;
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            String body = """
                    {"memberId":"%s","registeredBy":"%s","bookIds":["%s"],"dueAt":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(86400));
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/loans"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            event = eventId(UUID.fromString(JsonPath.parse(response.body()).read("$.id", String.class)));
            assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get("/actuator/health").statusCode()).isEqualTo(503);
            // More infra failures than the message retry limit must not park the event.
            for (int attempt = 0; attempt < 4; attempt++) {
                makeDue(event);
                assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.INFRASTRUCTURE_FAILURE);
            }
            assertThat(status(event)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select message_failures from outbox_events where event_id = ?", Integer.class, event)).isZero();
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        makeDue(event);
        // Fresh application context shares only durable DB/broker state; no in-memory publisher state.
        try (var fresh = new SpringApplicationBuilder(LoansApplication.class).web(WebApplicationType.NONE)
                .initializers(context -> TestPropertyValues.of(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.rabbitmq.host=" + RABBIT.getHost(),
                        "spring.rabbitmq.port=" + RABBIT.getMappedPort(5672),
                        "spring.rabbitmq.username=" + USER, "spring.rabbitmq.password=" + PASSWORD,
                        "library.outbox.enabled=true", "library.outbox.poll-interval-ms=20").applyTo(context)).run()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> status(event).equals("SENT"));
        }
        assertThat(status(event)).isEqualTo("SENT");
        assertThat(receive(MessagingTopology.CATALOG_REQUESTS).getMessageProperties().getMessageId()).isEqualTo(event.toString());
    }

    @Test
    void crashWindowAfterAckCanRepublishSameEventWithoutLosingIt() {
        UUID event = eventId(createLoan());
        jdbc.execute("alter table outbox_events add constraint reject_sent_test check (status <> 'SENT') not valid");
        try {
            assertThatThrownBy(dispatcher::publishNext).isInstanceOf(DataAccessException.class);
            assertThat(status(event)).isEqualTo("PENDING");
        } finally {
            jdbc.execute("alter table outbox_events drop constraint reject_sent_test");
        }
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        var first = receive(MessagingTopology.CATALOG_REQUESTS);
        var second = receive(MessagingTopology.CATALOG_REQUESTS);
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(second.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(first.getBody()).isEqualTo(second.getBody());
    }

    @Test
    void concurrentWorkersSkipLockedRowsWithoutDoublePublishing() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < 8; index++) {
            ids.add(eventId(createLoan()).toString());
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 2; worker++) {
                tasks.add(() -> {
                    for (int count = 0; count < 9; count++) {
                        var result = dispatcher.publishNext();
                        if (result == OutboxDispatcher.Result.EMPTY) break;
                        assertThat(result).isEqualTo(OutboxDispatcher.Result.SENT);
                    }
                    return null;
                });
            }
            for (var result : pool.invokeAll(tasks)) result.get(20, TimeUnit.SECONDS);
        }
        Set<String> delivered = new HashSet<>();
        for (int index = 0; index < 8; index++) {
            assertThat(delivered.add(receive(MessagingTopology.CATALOG_REQUESTS).getMessageProperties().getMessageId())).isTrue();
        }
        assertThat(delivered).isEqualTo(ids);
        assertThat(rabbit.receive(MessagingTopology.CATALOG_REQUESTS, 100)).isNull();
    }

    @Test
    void persistentMessageSurvivesBrokerAppRestart() throws Exception {
        UUID event = eventId(createLoan());
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        var message = receive(MessagingTopology.CATALOG_REQUESTS);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void rejectedDeliveryGoesToDurableDeadLetterQueue() {
        UUID event = eventId(createLoan());
        dispatcher.publishNext();
        rabbit.execute(channel -> {
            var delivery = channel.basicGet(MessagingTopology.CATALOG_REQUESTS, false);
            assertThat(delivery).isNotNull();
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
            return null;
        });
        var dead = receive(MessagingTopology.CATALOG_DLQ);
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    @Test
    void repeatedRequeuesAreLimitedByQuorumDeliveryLimit() {
        UUID event = eventId(createLoan());
        dispatcher.publishNext();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            rabbit.execute(channel -> {
                var delivery = channel.basicGet(MessagingTopology.CATALOG_REQUESTS, false);
                if (delivery != null) {
                    // RabbitMQ 4.3 basic.nack does not count as a failed delivery; reject does.
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                }
                return null;
            });
            var dead = rabbit.receive(MessagingTopology.CATALOG_DLQ, 100);
            assertThat(dead).isNotNull();
            assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(event.toString());
        });
    }
}

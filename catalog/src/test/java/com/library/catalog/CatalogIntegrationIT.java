package com.library.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import com.jayway.jsonpath.JsonPath;
import com.rabbitmq.client.Channel;
import com.library.catalog.books.BookService;
import com.library.catalog.messaging.*;
import com.library.catalog.outbox.OutboxDispatcher;
import com.library.catalog.reservation.ReservationService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "library.outbox.enabled=false", "catalog.consumer.enabled=false", "catalog.consumer.retry-backoff-ms=2000",
        "spring.datasource.hikari.connection-timeout=1000"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CatalogIntegrationIT {
    private static final String USER = "catalog_" + UUID.randomUUID();
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> PG.getJdbcUrl() + "&connectTimeout=1&socketTimeout=1");
        properties.add("spring.datasource.username", PG::getUsername);
        properties.add("spring.datasource.password", PG::getPassword);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> USER);
        properties.add("spring.rabbitmq.password", () -> PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired BookService books;
    @Autowired ReservationService reservations;
    @Autowired com.library.catalog.reservation.ReleaseService releases;
    @Autowired LoanRequestCodec codec;
    @Autowired LoanRequestedListener listener;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired JsonMapper mapper;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetDisposableDatabaseAndBroker() {
        registry.getListenerContainer(LoanRequestedListener.ID).stop();
        jdbc.update("delete from outbox_events");
        jdbc.update("delete from reservation_books");
        jdbc.update("delete from reservations");
        jdbc.update("delete from inbox_events");
        jdbc.update("delete from books");
        admin.initialize();
        for (String queue : List.of(MessagingTopology.CATALOG_REQUESTS, MessagingTopology.CATALOG_DLQ,
                MessagingTopology.LOAN_RESULTS, MessagingTopology.LOAN_RESULTS_DLQ)) admin.purgeQueue(queue, false);
    }

    @AfterEach
    void stopListener() { registry.getListenerContainer(LoanRequestedListener.ID).stop(); }

    private LibraryEvent<LoanRequested> request(UUID loan, UUID event, List<UUID> ids) {
        return new LibraryEvent<>(event, "LoanRequested", 1, Instant.parse("2026-10-14T10:00:00Z"), loan,
                UUID.randomUUID(), UUID.randomUUID(), null,
                new LoanRequested(UUID.randomUUID(), ids, Instant.parse("2026-11-14T10:00:00Z")));
    }
    private LibraryEvent<LoanRequested> request(List<UUID> ids) { return request(UUID.randomUUID(), UUID.randomUUID(), ids); }
    private LibraryEvent<LoanRequested> withEvent(LibraryEvent<LoanRequested> original, UUID event, List<UUID> ids) {
        return new LibraryEvent<>(event, original.eventType(), 1, original.occurredAt(), original.loanId(),
                original.sagaId(), original.correlationId(), original.causationId(),
                new LoanRequested(original.payload().memberId(), ids, original.payload().dueAt()));
    }
    private LoanRequestCodec.Request decode(LibraryEvent<LoanRequested> event) { return codec.decode(mapper.writeValueAsBytes(event)); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private UUID book(int copies) { return books.create("Título de teste", copies).id(); }
    private void send(LibraryEvent<LoanRequested> event) {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbit.send(MessagingTopology.EVENTS, MessagingTopology.LOAN_REQUESTED,
                new Message(mapper.writeValueAsBytes(event), properties));
    }
    private Message receive(String queue) {
        var message = rabbit.receive(queue, 10000);
        assertThat(message).isNotNull();
        return message;
    }
    private void assertProcessedOnce(int remaining, UUID book) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(count("reservations")).isEqualTo(1);
            assertThat(count("outbox_events")).isEqualTo(1);
            assertThat(books.find(book).availableCopies()).isEqualTo(remaining);
        });
    }

    @Test
    void httpCreatesAndFindsTitleWithStockAndReturnsSafe404And400() throws Exception {
        var created = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/books"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"title\":\"Domain Driven Design\",\"totalCopies\":2}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.parse(created.body()).read("$.id", String.class));
        assertThat(books.find(id).availableCopies()).isEqualTo(2);
        for (String path : List.of("/books/" + id, "/books/" + UUID.randomUUID(), "/books/not-a-uuid")) {
            var result = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(path.endsWith(id.toString()) ? 200 : path.endsWith("not-a-uuid") ? 400 : 404);
            assertThat(result.body()).doesNotContain("stackTrace", "java.util.UUID");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"\",\"totalCopies\":1}",
            "{\"title\":\"Título\",\"totalCopies\":-1}",
            "{\"title\":\"Título\",\"totalCopies\":null}",
            "{\"title\":null,\"totalCopies\":1}"})
    void invalidBookInputReturns400WithoutCreatingInventory(String body) throws Exception {
        var result = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/books"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(result.headers().firstValue("content-type").orElseThrow()).startsWith("application/problem+json");
        assertThat(count("books")).isZero();
    }

    @Test
    void upgradesExistingBookSchemaWithoutLosingStock() {
        var config = Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("upgrade_test").defaultSchema("upgrade_test");
        config.target("1").load().migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into upgrade_test.books(id,title,total_copies,available_copies) values (?, 'Título existente', 2, 1)", id);
        config.target("latest").load().migrate();
        assertThat(jdbc.queryForObject("select available_copies from upgrade_test.books where id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_copies from upgrade_test.books where id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from upgrade_test.reservations", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from upgrade_test.inbox_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from upgrade_test.outbox_events", Long.class)).isZero();
    }

    @Test
    void allBooksReservedAndResultPreservesCorrelationAndCausation() {
        UUID first = book(2), second = book(1);
        var event = request(List.of(first, second));
        assertThat(reservations.reserve(decode(event))).isEqualTo(ReservationService.Outcome.RESERVED);
        assertThat(books.find(first).availableCopies()).isEqualTo(1);
        assertThat(books.find(second).availableCopies()).isZero();
        assertThat(count("inbox_events")).isEqualTo(1);
        assertThat(count("reservation_books")).isEqualTo(2);
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        var result = receive(MessagingTopology.LOAN_RESULTS);
        var json = JsonPath.parse(new String(result.getBody(), StandardCharsets.UTF_8));
        assertThat(json.read("$.eventType", String.class)).isEqualTo("BooksReserved");
        assertThat(json.read("$.loanId", String.class)).isEqualTo(event.loanId().toString());
        assertThat(json.read("$.sagaId", String.class)).isEqualTo(event.sagaId().toString());
        assertThat(json.read("$.correlationId", String.class)).isEqualTo(event.correlationId().toString());
        assertThat(json.read("$.causationId", String.class)).isEqualTo(event.eventId().toString());
        assertThat(json.read("$.payload.bookIds.length()", Integer.class)).isEqualTo(2);
        assertThat(result.getMessageProperties().getCorrelationId()).isEqualTo(event.correlationId().toString());
        assertThat(result.getMessageProperties().getHeaders().get("sagaId")).isEqualTo(event.sagaId().toString());
    }

    @Test
    void unknownAndUnavailableTitlesRejectWithoutPartialReservation() {
        UUID available = book(1), empty = book(0), missing = UUID.randomUUID();
        var event = request(List.of(available, empty, missing));
        assertThat(reservations.reserve(decode(event))).isEqualTo(ReservationService.Outcome.REJECTED);
        assertThat(books.find(available).availableCopies()).isEqualTo(1);
        assertThat(count("reservation_books")).isZero();
        assertThat(count("inbox_events")).isEqualTo(1);
        dispatcher.publishNext();
        var json = JsonPath.parse(new String(receive(MessagingTopology.LOAN_RESULTS).getBody(), StandardCharsets.UTF_8));
        assertThat(json.read("$.eventType", String.class)).isEqualTo("BooksReservationRejected");
        assertThat(json.read("$.payload.reasonCode", String.class)).isEqualTo("BOOKS_UNAVAILABLE");
        List<String> unavailable = json.read("$.payload.unavailableBookIds");
        assertThat(unavailable).containsExactlyInAnyOrder(empty.toString(), missing.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateEventOrNewEventForSameLoanDoesNotReserveAgain(boolean newEvent) {
        UUID id = book(2);
        var event = request(List.of(id));
        reservations.reserve(decode(event));
        var repeated = withEvent(event, newEvent ? UUID.randomUUID() : event.eventId(), event.payload().bookIds());
        assertThat(reservations.reserve(decode(repeated))).isEqualTo(ReservationService.Outcome.DUPLICATE);
        assertThat(count("inbox_events")).isEqualTo(newEvent ? 2 : 1);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(books.find(id).availableCopies()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictingReuseOfEventOrLoanIdIsPermanentAndRollsBackNewInbox(boolean newEvent) {
        UUID first = book(1), second = book(1);
        var original = request(List.of(first));
        reservations.reserve(decode(original));
        var conflicting = withEvent(original, newEvent ? UUID.randomUUID() : original.eventId(), List.of(second));
        assertThatThrownBy(() -> reservations.reserve(decode(conflicting))).isInstanceOf(InvalidEvent.class);
        assertThat(count("inbox_events")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(books.find(second).availableCopies()).isEqualTo(1);
    }

    @Test
    void concurrentLoansCannotReserveMoreThanAvailableAndLockOrderIsStable() throws Exception {
        UUID first = book(1), second = book(1);
        var left = request(List.of(first, second));
        var right = request(List.of(second, first));
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<ReservationService.Outcome>>of(
                    () -> { start.await(5, TimeUnit.SECONDS); return reservations.reserve(decode(left)); },
                    () -> { start.await(5, TimeUnit.SECONDS); return reservations.reserve(decode(right)); });
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ReservationService.Outcome.RESERVED, ReservationService.Outcome.REJECTED);
        }
        assertThat(books.find(first).availableCopies()).isZero();
        assertThat(books.find(second).availableCopies()).isZero();
        assertThat(count("reservation_books")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousDuplicatesAreSerializedByInboxAndLoanUniqueness(boolean newEvent) throws Exception {
        UUID id = book(1);
        var event = request(List.of(id));
        var repeated = withEvent(event, newEvent ? UUID.randomUUID() : event.eventId(), event.payload().bookIds());
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<ReservationService.Outcome>>of(
                    () -> { start.await(5, TimeUnit.SECONDS); return reservations.reserve(decode(event)); },
                    () -> { start.await(5, TimeUnit.SECONDS); return reservations.reserve(decode(repeated)); });
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ReservationService.Outcome.RESERVED, ReservationService.Outcome.DUPLICATE);
        }
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(books.find(id).availableCopies()).isZero();
    }

    @Test
    void outboxFailureRollsBackInboxReservationAndInventory() {
        UUID id = book(1);
        jdbc.execute("alter table outbox_events add constraint reject_test_outbox check (event_type <> 'BooksReserved') not valid");
        try {
            assertThatThrownBy(() -> reservations.reserve(decode(request(List.of(id))))).isInstanceOf(DataAccessException.class);
            assertThat(count("inbox_events")).isZero();
            assertThat(count("reservations")).isZero();
            assertThat(count("reservation_books")).isZero();
            assertThat(count("outbox_events")).isZero();
            assertThat(books.find(id).availableCopies()).isEqualTo(1);
        } finally {
            jdbc.execute("alter table outbox_events drop constraint reject_test_outbox");
        }
    }

    @Test
    void listenerAcknowledgesOnlyAfterCommitAndAckFailureCanBeRedelivered() throws Exception {
        UUID id = book(1);
        var event = request(List.of(id));
        var message = new Message(mapper.writeValueAsBytes(event), new MessageProperties());
        message.getMessageProperties().setDeliveryTag(1);
        var ackCalls = new AtomicInteger();
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class<?>[]{Channel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("basicAck")) {
                        assertThat(count("inbox_events")).isEqualTo(1);
                        assertThat(count("reservations")).isEqualTo(1);
                        assertThat(count("outbox_events")).isEqualTo(1);
                        if (ackCalls.getAndIncrement() == 0) throw new IOException("simulated-ack-failure");
                    } else {
                        throw new AssertionError("Unexpected channel call: " + method.getName());
                    }
                    return null;
                });
        assertThatThrownBy(() -> listener.receive(message, channel)).isInstanceOf(IOException.class);
        listener.receive(message, channel);
        assertThat(ackCalls).hasValue(2);
        assertThat(books.find(id).availableCopies()).isZero();
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void realListenerDeduplicatesReDeliveredMessages() {
        UUID id = book(1);
        var event = request(List.of(id));
        registry.getListenerContainer(LoanRequestedListener.ID).start();
        send(event); send(event);
        assertProcessedOnce(0, id);
        registry.getListenerContainer(LoanRequestedListener.ID).stop();
        assertThat(count("inbox_events")).isEqualTo(1);
        assertThat(rabbit.receive(MessagingTopology.CATALOG_DLQ, 100)).isNull();
    }

    @Test
    void invalidJsonIsDeadLetteredWithoutWritingBusinessState() {
        registry.getListenerContainer(LoanRequestedListener.ID).start();
        rabbit.send(MessagingTopology.EVENTS, MessagingTopology.LOAN_REQUESTED,
                new Message("{invalid-marker".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertThat(receive(MessagingTopology.CATALOG_DLQ).getMessageProperties().getHeaders()).containsKey("x-death");
        assertThat(count("inbox_events")).isZero();
        assertThat(count("reservations")).isZero();
    }

    @Test
    void transientFailureBeforeCommitIsNotAckedAndRetriesEntireTransaction() {
        UUID id = book(1);
        jdbc.execute("create sequence test_attempts");
        jdbc.execute("""
                create function test_fail_once() returns trigger language plpgsql as $$
                begin
                  if nextval('test_attempts') = 1 then raise exception 'simulated-transient-error'; end if;
                  return NEW;
                end; $$
                """);
        jdbc.execute("create trigger test_before_outbox before insert on outbox_events for each row execute function test_fail_once()");
        try {
            registry.getListenerContainer(LoanRequestedListener.ID).start();
            send(request(List.of(id)));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("select is_called from test_attempts", Boolean.class));
            assertThat(count("inbox_events")).isZero();
            assertThat(count("reservations")).isZero();
            assertThat(books.find(id).availableCopies()).isEqualTo(1);
            assertProcessedOnce(0, id);
            assertThat(rabbit.receive(MessagingTopology.CATALOG_DLQ, 100)).isNull();
        } finally {
            registry.getListenerContainer(LoanRequestedListener.ID).stop();
            jdbc.execute("drop trigger test_before_outbox on outbox_events");
            jdbc.execute("drop function test_fail_once()");
            jdbc.execute("drop sequence test_attempts");
        }
    }

    @Test
    void shortDatabaseOutageRecoversWithBackoffInsteadOfInstantDlq() throws Exception {
        UUID id = book(1);
        var client = DockerClientFactory.instance().client();
        registry.getListenerContainer(LoanRequestedListener.ID).start();
        client.pauseContainerCmd(PG.getContainerId()).exec();
        try {
            send(request(List.of(id)));
            Thread.sleep(2500); // Deliberate outage duration; retry backoff prevents immediate delivery-limit exhaustion.
        } finally {
            client.unpauseContainerCmd(PG.getContainerId()).exec();
        }
        assertProcessedOnce(0, id);
        assertThat(rabbit.receive(MessagingTopology.CATALOG_DLQ, 100)).isNull();
    }

    @Test
    void brokerOutageDoesNotLoseReservationResult() throws Exception {
        UUID id = book(1);
        UUID event;
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            reservations.reserve(decode(request(List.of(id))));
            event = jdbc.queryForObject("select event_id from outbox_events", UUID.class);
            assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.INFRASTRUCTURE_FAILURE);
            assertThat(jdbc.queryForObject("select status from outbox_events", String.class)).isEqualTo("PENDING");
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        jdbc.update("update outbox_events set next_attempt_at='2000-01-01T00:00:00Z'");
        assertThat(dispatcher.publishNext()).isEqualTo(OutboxDispatcher.Result.SENT);
        assertThat(receive(MessagingTopology.LOAN_RESULTS).getMessageProperties().getMessageId()).isEqualTo(event.toString());
    }
    private LibraryEvent<ReleaseCodec.Payload> releaseFor(LibraryEvent<LoanRequested> request) {
        return new LibraryEvent<>(UUID.randomUUID(),"BooksReleaseRequested",1,Instant.now(),request.loanId(),
                request.loanId(),request.loanId(),request.eventId(),new ReleaseCodec.Payload(request.eventId()));
    }
    @Test void releasesReservedStockOnceAndReplaysConfirmation() {
        var book=books.create("Release test",1);
        var input=request(List.of(book.id()));
        reservations.reserve(codec.decode(mapper.writeValueAsBytes(input)));
        var release=releaseFor(input);
        releases.release(release); releases.release(release);
        assertThat(books.find(book.id()).availableCopies()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from reservations where loan_id=?",String.class,input.loanId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type='BooksReleased'",Long.class)).isEqualTo(1);
    }
    @Test void releaseBeforeRequestPreventsLateReservation() {
        var book=books.create("Late test",1);
        var input=request(List.of(book.id()));
        releases.release(releaseFor(input));
        assertThat(reservations.reserve(codec.decode(mapper.writeValueAsBytes(input)))).isEqualTo(ReservationService.Outcome.DUPLICATE);
        assertThat(books.find(book.id()).availableCopies()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reservations",Long.class)).isZero();
    }
    @Test void releaseOutboxFailureRollsBackStockAndBarrier() {
        var book=books.create("Rollback release",1);
        var input=request(List.of(book.id()));
        reservations.reserve(codec.decode(mapper.writeValueAsBytes(input)));
        var release=releaseFor(input);
        jdbc.execute("alter table outbox_events add constraint test_no_release check(event_type<>'BooksReleased') not valid");
        try {
            assertThatThrownBy(() -> releases.release(release)).isInstanceOf(RuntimeException.class);
            assertThat(books.find(book.id()).availableCopies()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from release_barriers where loan_id=?",Long.class,input.loanId())).isZero();
        } finally { jdbc.execute("alter table outbox_events drop constraint test_no_release"); }
        releases.release(release);
        assertThat(books.find(book.id()).availableCopies()).isEqualTo(1);
    }
    @Test void concurrentReleaseAndReservationNeverLeaveReservedStock() throws Exception {
        var book=books.create("Race release",1); var input=request(List.of(book.id()));
        var release=releaseFor(input);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> reservations.reserve(codec.decode(mapper.writeValueAsBytes(input))));
            var b=pool.submit(() -> { releases.release(release); return true; });
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        assertThat(books.find(book.id()).availableCopies()).isEqualTo(1);
    }

}

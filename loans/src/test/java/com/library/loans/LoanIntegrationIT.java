package com.library.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;
import com.library.loans.domain.Loan;
import com.library.loans.repository.LoanRepository;
import com.library.loans.service.LoanService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "library.outbox.enabled=false")
@Testcontainers
class LoanIntegrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String AMQP_USER = "test_" + UUID.randomUUID();
    private static final String AMQP_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.3.6-management")
            .withEnv("RABBITMQ_DEFAULT_USER", AMQP_USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", AMQP_PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> AMQP_USER);
        properties.add("spring.rabbitmq.password", () -> AMQP_PASSWORD);
    }

    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> request(String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessChecksDatabaseWithoutExposingDetails() throws Exception {
        var readiness = request("/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        var json = JsonPath.parse(readiness.body());
        assertThat(json.read("$.status", String.class)).isEqualTo("UP");
        assertThat(readiness.body()).doesNotContain("components", AMQP_PASSWORD);
        assertThat(request("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void createsPendingLoanOverHttpAndPersistsBooks() throws Exception {
        UUID member = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        String body = """
                {"memberId":"%s","registeredBy":"%s","bookIds":["%s"],"dueAt":"%s"}
                """.formatted(member, operator, book, Instant.now().plusSeconds(86400));
        var response = request("/loans", body);
        assertThat(response.statusCode()).isEqualTo(201);
        var json = JsonPath.parse(response.body());
        assertThat(json.read("$.status", String.class)).isEqualTo("PENDING");
        assertThat(json.read("$.memberId", String.class)).isEqualTo(member.toString());
        UUID id = UUID.fromString(json.read("$.id", String.class));
        assertThat(jdbc.queryForObject("select status from loans where id = ?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForList("select book_id from loan_books where loan_id = ?", UUID.class, id))
                .containsExactly(book);
    }

    @Test
    void rejectsEmptyBooksOverHttpWithoutPersistingLoan() throws Exception {
        UUID member = UUID.randomUUID();
        String body = """
                {"memberId":"%s","registeredBy":"%s","bookIds":[],"dueAt":"%s"}
                """.formatted(member, UUID.randomUUID(), Instant.now().plusSeconds(86400));
        var response = request("/loans", body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(JsonPath.parse(response.body()).read("$.errors[0].field", String.class))
                .isEqualTo("bookIds");
        assertThat(jdbc.queryForObject("select count(*) from loans where member_id = ?", Long.class, member))
                .isZero();
    }

    static Stream<Arguments> unreadableRequests() {
        String member = UUID.randomUUID().toString();
        String operator = UUID.randomUUID().toString();
        String book = UUID.randomUUID().toString();
        String due = Instant.now().plusSeconds(86400).toString();
        String body = """
                {"memberId":"%s","registeredBy":"%s","bookIds":["%s"],"dueAt":"%s"}
                """.formatted(member, operator, book, due);
        return Stream.of(
                Arguments.of("JSON truncado", "{\"memberId\":"),
                Arguments.of("Corpo vazio", ""),
                Arguments.of("Corpo null", "null"),
                Arguments.of("Array no lugar do objeto", "[]"),
                Arguments.of("memberId inválido", body.replace(member, "invalid-marker")),
                Arguments.of("registeredBy inválido", body.replace(operator, "invalid-marker")),
                Arguments.of("UUID de livro inválido", body.replace(book, "invalid-marker")),
                Arguments.of("bookIds fora do formato array", body.replace(
                        "[\"" + book + "\"]", "\"invalid-marker\"")),
                Arguments.of("Data inválida", body.replace(due, "invalid-marker")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableRequests")
    void rejectsUnreadableRequestsWithoutExposingInputOrPersistingData(String name, String body)
            throws Exception {
        Long loanCount = jdbc.queryForObject("select count(*) from loans", Long.class);
        Long bookCount = jdbc.queryForObject("select count(*) from loan_books", Long.class);
        var response = request("/loans", body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        var problem = JsonPath.parse(response.body());
        assertThat(problem.read("$.status", Integer.class)).isEqualTo(400);
        assertThat(problem.read("$.title", String.class)).isEqualTo("Requisição inválida");
        assertThat(problem.read("$.detail", String.class))
                .isEqualTo("Corpo da requisição inválido ou malformado");
        assertThat(response.body()).doesNotContain(
                "invalid-marker", "JSON parse error", "Cannot deserialize", "com.fasterxml",
                "tools.jackson", "java.util.UUID", "stackTrace", "exception");
        assertThat(jdbc.queryForObject("select count(*) from loans", Long.class)).isEqualTo(loanCount);
        assertThat(jdbc.queryForObject("select count(*) from loan_books", Long.class)).isEqualTo(bookCount);
    }

    @Test
    void upgradesExistingV1DataWithoutLosingLoanOrBooks() {
        var config = Flyway.configure().dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("upgrade_test").defaultSchema("upgrade_test");
        config.target("1").load().migrate();
        UUID id = UUID.randomUUID();
        UUID book = UUID.randomUUID();
        jdbc.update("""
                insert into upgrade_test.loans
                (id, member_id, registered_by, status, created_at, due_at)
                values (?, ?, ?, 'PENDING', now(), now() + interval '1 day')
                """, id, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("insert into upgrade_test.loan_books (loan_id, book_id) values (?, ?)", id, book);
        config.target("latest").load().migrate();
        assertThat(jdbc.queryForObject("select version from upgrade_test.loans where id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("select book_id from upgrade_test.loan_books where loan_id = ?", UUID.class, id))
                .isEqualTo(book);
    }

    @Autowired LoanService loanService;
    @Autowired LoanRepository loans;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void rollsBackLoanAndBooksAfterFlushingServiceCreation() {
        var tx = new TransactionTemplate(transactionManager);
        var savedId = new AtomicReference<UUID>();
        var books = Set.of(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var response = loanService.create(UUID.randomUUID(), UUID.randomUUID(), books,
                    Instant.now().plusSeconds(86400));
            savedId.set(response.id());
            loans.flush();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events where loan_id = ?",
                    Long.class, savedId.get())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from loans where id = ?",
                    Long.class, savedId.get())).isEqualTo(1L);
            assertThat(jdbc.queryForList("select book_id from loan_books where loan_id = ?",
                    UUID.class, savedId.get())).containsExactlyInAnyOrderElementsOf(books);
            throw new IllegalStateException("rollback-probe");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback-probe");

        assertThat(savedId.get()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where loan_id = ?",
                Long.class, savedId.get())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from loans where id = ?",
                Long.class, savedId.get())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from loan_books where loan_id = ?",
                Long.class, savedId.get())).isZero();
    }

    @Test
    void staleTransactionCannotOverwriteCommittedWinner() {
        var tx = new TransactionTemplate(transactionManager);
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var books = Set.of(UUID.randomUUID(), UUID.randomUUID());
        var created = Instant.parse("2026-10-13T12:00:00Z");
        UUID id = tx.execute(status -> loans.save(Loan.create(
                UUID.randomUUID(), UUID.randomUUID(), books,
                created, created.plusSeconds(86400))).getId());

        assertThat(jdbc.queryForObject("select version from loans where id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForList("select book_id from loan_books where loan_id = ?", UUID.class, id))
                .containsExactlyInAnyOrderElementsOf(books);

        // Both transactions read version 0. B commits while A is suspended.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Loan stale = loans.findById(id).orElseThrow();
            stale.activate();
            independent.executeWithoutResult(other -> loans.findById(id).orElseThrow().cancel());
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jdbc.queryForObject("select status from loans where id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select version from loans where id = ?", Long.class, id))
                .isEqualTo(1L);
        assertThat(jdbc.queryForList("select book_id from loan_books where loan_id = ?", UUID.class, id))
                .containsExactlyInAnyOrderElementsOf(books);
    }
}

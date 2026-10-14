package com.library.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.library.loans.domain.Loan;
import com.library.loans.repository.LoanRepository;

@SpringBootTest
@Testcontainers
class LoanConcurrencyIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired LoanRepository loans;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

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

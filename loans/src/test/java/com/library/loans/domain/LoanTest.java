package com.library.loans.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoanTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant CREATED = CLOCK.instant();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID BOOK = UUID.randomUUID();

    private Loan create(Set<UUID> books, Instant dueAt) {
        return Loan.create(MEMBER, OPERATOR, books, CREATED, dueAt);
    }

    @Test
    void createsPendingLoanWithIndependentImmutableBookIds() {
        var original = new HashSet<>(Set.of(BOOK));
        var loan = create(original, CREATED.plusSeconds(3600));
        original.clear();
        assertThat(loan.getId()).isNotNull();
        assertThat(loan.getMemberId()).isEqualTo(MEMBER);
        assertThat(loan.getRegisteredBy()).isEqualTo(OPERATOR);
        assertThat(loan.getCreatedAt()).isEqualTo(CREATED);
        assertThat(loan.getDueAt()).isEqualTo(CREATED.plusSeconds(3600));
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.PENDING);
        assertThat(loan.getReturnedAt()).isNull();
        assertThat(loan.getBookIds()).containsExactly(BOOK);
        assertThatThrownBy(() -> loan.getBookIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void rejectsDeadlineAtOrBeforeCreation(long seconds) {
        assertThatThrownBy(() -> create(Set.of(BOOK), CREATED.plusSeconds(seconds)))
                .isInstanceOf(InvalidLoanException.class);
    }

    @Test
    void rejectsEmptyBooks() {
        assertThatThrownBy(() -> create(Set.of(), CREATED.plusSeconds(1)))
                .isInstanceOf(InvalidLoanException.class);
    }

    @Test
    void rejectsNullBookId() {
        var books = new HashSet<>(Set.of(BOOK));
        books.add(null);
        assertThatThrownBy(() -> create(books, CREATED.plusSeconds(1)))
                .isInstanceOf(InvalidLoanException.class);
    }

    @Test
    void rejectsMissingRequiredValues() {
        Instant due = CREATED.plusSeconds(1);
        assertThatThrownBy(() -> Loan.create(null, OPERATOR, Set.of(BOOK), CREATED, due))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Loan.create(MEMBER, null, Set.of(BOOK), CREATED, due))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Loan.create(MEMBER, OPERATOR, null, CREATED, due))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Loan.create(MEMBER, OPERATOR, Set.of(BOOK), null, due))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Loan.create(MEMBER, OPERATOR, Set.of(BOOK), CREATED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void activatesIdempotentlyAndRejectsCancellationAfterActivation() {
        var loan = create(Set.of(BOOK), CREATED.plusSeconds(1));
        loan.activate();
        loan.activate();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThatThrownBy(loan::cancel).isInstanceOf(IllegalStateException.class);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void cancelsIdempotentlyAndRejectsActivationAfterCancellation() {
        var loan = create(Set.of(BOOK), CREATED.plusSeconds(1));
        loan.cancel();
        loan.cancel();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThatThrownBy(loan::activate).isInstanceOf(IllegalStateException.class);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
    }
}

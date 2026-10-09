package com.library.loans.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "loans")
public class Loan {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LoanStatus status;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "registered_by", nullable = false)
    private UUID registeredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @ElementCollection
    @CollectionTable(name = "loan_books", joinColumns = @JoinColumn(name = "loan_id"))
    @Column(name = "book_id", nullable = false)
    private Set<UUID> bookIds = new HashSet<>();

    protected Loan() {
    }

    public UUID getId() {
        return id;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getRegisteredBy() {
        return registeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public Set<UUID> getBookIds() {
        return Set.copyOf(bookIds);
    }

    public void activate() {
        if (status == LoanStatus.ACTIVE) {
            return;
        }

        if (status != LoanStatus.PENDING) {
            throw new IllegalStateException(
                    "Somente empréstimos pendentes podem ser ativados");
        }

        status = LoanStatus.ACTIVE;
    }

    public void cancel() {
        if (status == LoanStatus.CANCELLED) {
            return;
        }

        if (status != LoanStatus.PENDING) {
            throw new IllegalStateException(
                    "Somente empréstimos pendentes podem ser cancelados");
        }

        status = LoanStatus.CANCELLED;
    }

    public static Loan create(
            UUID memberId,
            UUID registeredBy,
            Set<UUID> bookIds,
            Instant createdAt,
            Instant dueAt) {

        Objects.requireNonNull(memberId, "Membro é obrigatório");
        Objects.requireNonNull(registeredBy, "Operador é obrigatório");
        Objects.requireNonNull(bookIds, "Livros são obrigatórios");
        Objects.requireNonNull(createdAt, "Data de criação é obrigatória");
        Objects.requireNonNull(dueAt, "Prazo de devolução é obrigatório");

        if (bookIds.isEmpty()) {
            throw new InvalidLoanException(
                    "O empréstimo deve conter pelo menos um livro");
        }

        if (bookIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidLoanException(
                    "Os identificadores dos livros não podem ser nulos");
        }

        if (!dueAt.isAfter(createdAt)) {
            throw new InvalidLoanException(
                    "O prazo de devolução deve ser posterior à criação");
        }

        Loan loan = new Loan();
        loan.id = UUID.randomUUID();
        loan.memberId = memberId;
        loan.registeredBy = registeredBy;
        loan.bookIds = new HashSet<>(bookIds);
        loan.status = LoanStatus.PENDING;
        loan.createdAt = createdAt;
        loan.dueAt = dueAt;

        return loan;
    }
}
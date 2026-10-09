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

}
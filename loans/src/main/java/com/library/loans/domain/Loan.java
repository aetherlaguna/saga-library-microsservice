package com.library.loans.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
}
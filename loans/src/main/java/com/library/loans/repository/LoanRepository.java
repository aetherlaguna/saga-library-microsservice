package com.library.loans.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.library.loans.domain.Loan;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
}
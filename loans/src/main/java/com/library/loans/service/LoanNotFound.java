package com.library.loans.service;

public class LoanNotFound extends RuntimeException {
    public LoanNotFound() { super("Empréstimo não encontrado"); }
}

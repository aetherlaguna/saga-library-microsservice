package com.library.loans.domain;

public class InvalidLoanException extends RuntimeException {

    public InvalidLoanException(String message) {
        super(message);
    }
}
package com.library.loans.messaging;

public class InvalidCatalogResult extends RuntimeException {
    public InvalidCatalogResult(String code) { super(code); }
}

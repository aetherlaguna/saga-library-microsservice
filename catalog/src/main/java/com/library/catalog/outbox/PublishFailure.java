package com.library.catalog.outbox;

public class PublishFailure extends RuntimeException {
    public enum Code {
        BROKER_UNAVAILABLE(true), CONFIRM_TIMEOUT(true), CONFIRM_FAILED(true), INTERRUPTED(true),
        UNROUTABLE(false), NACK(false);

        private final boolean infrastructure;
        Code(boolean infrastructure) { this.infrastructure = infrastructure; }
        public boolean infrastructure() { return infrastructure; }
    }

    private final Code code;
    public PublishFailure(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() { return code; }
}

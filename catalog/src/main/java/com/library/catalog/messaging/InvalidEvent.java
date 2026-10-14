package com.library.catalog.messaging;

public class InvalidEvent extends RuntimeException {
    public InvalidEvent(String code) { super(code); }
}

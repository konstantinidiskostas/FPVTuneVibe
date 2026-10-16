package com.example.fpv.blackbox;

public class EndOfStreamException extends BlackboxException {
    public EndOfStreamException(String message) {
        super(message);
    }
}
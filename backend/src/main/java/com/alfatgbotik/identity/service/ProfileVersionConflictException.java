package com.alfatgbotik.identity.service;

public class ProfileVersionConflictException extends RuntimeException {

    public ProfileVersionConflictException() {
        super("Business profile version conflict");
    }

    public ProfileVersionConflictException(Throwable cause) {
        super("Business profile version conflict", cause);
    }
}

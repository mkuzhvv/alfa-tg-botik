package com.alfatgbotik.identity.service;

public class TelegramAuthUnavailableException extends RuntimeException {

    public TelegramAuthUnavailableException() {
        super("Telegram authentication is not configured");
    }
}

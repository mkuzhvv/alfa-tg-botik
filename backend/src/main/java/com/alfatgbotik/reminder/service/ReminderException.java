package com.alfatgbotik.reminder.service;

public class ReminderException extends RuntimeException {

    private final int status;
    private final String code;

    public ReminderException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}

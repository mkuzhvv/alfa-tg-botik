package com.alfatgbotik.plan.service;

public class PlanValidationException extends RuntimeException {

    private final String field;

    public PlanValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

package com.alfatgbotik.plan.service;

public class PlanConflictException extends RuntimeException {

    private final String code;

    private PlanConflictException(String code) {
        super(code);
        this.code = code;
    }

    public static PlanConflictException version() {
        return new PlanConflictException("VERSION_CONFLICT");
    }

    public static PlanConflictException archived() {
        return new PlanConflictException("PLAN_ARCHIVED");
    }

    public static PlanConflictException replacementRequired() {
        return new PlanConflictException("PLAN_REPLACEMENT_REQUIRED");
    }

    public String getCode() {
        return code;
    }
}

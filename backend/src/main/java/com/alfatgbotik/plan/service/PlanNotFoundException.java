package com.alfatgbotik.plan.service;

public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException() {
        super("Plan or step not found");
    }
}

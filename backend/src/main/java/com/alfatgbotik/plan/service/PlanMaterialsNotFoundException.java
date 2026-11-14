package com.alfatgbotik.plan.service;

public class PlanMaterialsNotFoundException extends RuntimeException {

    public PlanMaterialsNotFoundException() {
        super("Plan materials are unavailable in the active catalog");
    }
}

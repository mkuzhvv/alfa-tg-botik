package com.alfatgbotik.plan.model;

import java.util.List;
import java.util.UUID;

public record PlanProposal(String goal, List<Step> steps) {

    public record Step(String title, String description, List<UUID> materialIds) {
    }
}

package com.alfatgbotik.plan.model;

import java.time.Instant;
import java.util.UUID;

public record Plan(UUID id, UUID userId, String goal, PlanStatus status, long version,
                   Instant createdAt, Instant updatedAt) {
}

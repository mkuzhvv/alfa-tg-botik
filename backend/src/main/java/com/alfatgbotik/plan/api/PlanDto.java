package com.alfatgbotik.plan.api;

import com.alfatgbotik.plan.model.PlanStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanDto(UUID id, String goal, PlanStatus status, long version, List<PlanStepDto> steps,
                      Instant createdAt, Instant updatedAt) {
}

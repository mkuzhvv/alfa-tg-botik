package com.alfatgbotik.plan.api;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.plan.model.StepStatus;

import java.util.List;
import java.util.UUID;

public record PlanStepDto(UUID id, int position, String title, String description, StepStatus status,
                          List<MaterialCardDto> materials) {
}

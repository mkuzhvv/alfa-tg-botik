package com.alfatgbotik.plan.api;

import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.plan.model.StepStatus;
import tools.jackson.databind.JsonNode;

import java.util.Set;

public record StepUpdateRequest(long expectedVersion, StepStatus status) {

    public static StepUpdateRequest from(JsonNode body) {
        JsonRequest request = new JsonRequest(body, Set.of("expectedVersion", "status"));
        long version = request.requiredNonNegativeLong("expectedVersion");
        StepStatus status = request.nullableEnum("status", StepStatus.class);
        if (status == null) {
            throw RequestValidationException.invalid("status", "Field is required");
        }
        return new StepUpdateRequest(version, status);
    }
}

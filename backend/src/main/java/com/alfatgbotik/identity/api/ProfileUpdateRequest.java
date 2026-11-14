package com.alfatgbotik.identity.api;

import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.identity.model.BusinessStage;
import com.alfatgbotik.identity.model.Experience;
import com.alfatgbotik.identity.model.TaxRegime;
import com.alfatgbotik.identity.service.ProfileUpdateCommand;
import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.Set;

public final class ProfileUpdateRequest {

    private static final Set<String> FIELDS = Set.of("expectedVersion", "businessStage", "industry",
            "experience", "currentTask", "taxRegime", "timeZone");

    private ProfileUpdateRequest() {
    }

    public static ProfileUpdateCommand toCommand(JsonNode body) {
        JsonRequest request = new JsonRequest(body, FIELDS);
        ProfileUpdateCommand command = new ProfileUpdateCommand(request.requiredNonNegativeLong("expectedVersion"));
        if (request.contains("businessStage")) {
            command.businessStage(request.nullableEnum("businessStage", BusinessStage.class));
        }
        if (request.contains("industry")) {
            command.industry(request.nullableText("industry", 100));
        }
        if (request.contains("experience")) {
            command.experience(request.nullableEnum("experience", Experience.class));
        }
        if (request.contains("currentTask")) {
            command.currentTask(request.nullableText("currentTask", 500));
        }
        if (request.contains("taxRegime")) {
            command.taxRegime(request.nullableEnum("taxRegime", TaxRegime.class));
        }
        if (request.contains("timeZone")) {
            String timeZone = request.nullableText("timeZone", 64);
            if (timeZone != null && (!timeZone.matches("[A-Za-z0-9_+./-]+")
                    || !ZoneId.getAvailableZoneIds().contains(timeZone))) {
                throw RequestValidationException.invalid("timeZone", "Must be a recognized IANA time zone");
            }
            command.timeZone(timeZone);
        }
        if (!command.hasChanges()) {
            throw RequestValidationException.invalid("request", "At least one profile field must be provided");
        }
        return command;
    }
}

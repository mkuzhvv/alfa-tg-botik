package com.alfatgbotik.reminder.api;

import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.service.ReminderCreateCommand;
import com.alfatgbotik.reminder.service.ReminderUpdateCommand;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ReminderRequests {

    private ReminderRequests() {
    }

    public static ReminderCreateCommand create(JsonNode body) {
        JsonRequest request = new JsonRequest(body, Set.of("title", "schedule", "materialId", "planStepId"));
        return new ReminderCreateCommand(request.requiredText("title", 160), schedule(body.get("schedule")),
                nullableId(body, "materialId"), nullableId(body, "planStepId"));
    }

    public static ReminderUpdateCommand update(JsonNode body) {
        JsonRequest request = new JsonRequest(body, Set.of("expectedVersion", "title", "schedule", "status"));
        long version = request.requiredNonNegativeLong("expectedVersion");
        if (!request.contains("title") && !request.contains("schedule") && !request.contains("status")) {
            throw RequestValidationException.invalid("request", "Provide at least one field to update");
        }
        ReminderStatus status = null;
        if (request.contains("status")) {
            request.requiredText("status", 20);
            status = request.nullableEnum("status", ReminderStatus.class);
            if (status == ReminderStatus.COMPLETED) {
                throw RequestValidationException.invalid("status", "COMPLETED is assigned by the server");
            }
        }
        return new ReminderUpdateCommand(version, request.contains("title") ? request.requiredText("title", 160) : null,
                request.contains("schedule") ? schedule(body.get("schedule")) : null, status);
    }

    public static UUID uuid(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw RequestValidationException.malformed(field, "Expected a UUID");
        }
        return UUID.fromString(value);
    }

    public static ReminderStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ReminderStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw RequestValidationException.invalid("status", "Unsupported value");
        }
    }

    public static int pageNumber(String value, String field, int min, int max) {
        if (!value.matches("-?[0-9]+")) {
            throw RequestValidationException.malformed(field, "Expected an integer");
        }
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException ignored) {
        }
        throw RequestValidationException.invalid(field, "Expected " + min + " to " + max);
    }

    private static UUID nullableId(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null) {
            throw RequestValidationException.invalid(field, "Field is required; use null when not linked");
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw RequestValidationException.malformed(field, "Expected a UUID string or null");
        }
        return uuid(node.stringValue(), field);
    }

    private static ReminderSchedule schedule(JsonNode node) {
        if (node == null || node.isNull()) {
            throw RequestValidationException.invalid("schedule", "Field is required");
        }
        JsonRequest common = new JsonRequest(node,
                Set.of("type", "at", "timeZone", "localTime", "startDate", "dayOfWeek", "dayOfMonth"));
        common.requiredText("type", 20);
        ReminderSchedule.Type type = common.nullableEnum("type", ReminderSchedule.Type.class);
        Set<String> fields = new HashSet<>(Set.of("type", "timeZone"));
        if (type == ReminderSchedule.Type.ONCE) {
            fields.add("at");
        } else {
            fields.addAll(Set.of("localTime", "startDate"));
            if (type == ReminderSchedule.Type.WEEKLY) {
                fields.add("dayOfWeek");
            } else if (type == ReminderSchedule.Type.MONTHLY) {
                fields.add("dayOfMonth");
            }
        }
        JsonRequest request = new JsonRequest(node, fields);
        String zone = request.requiredText("timeZone", 64);
        Instant at = null;
        LocalTime time = null;
        LocalDate start = null;
        DayOfWeek day = null;
        Integer monthDay = null;
        try {
            if (type == ReminderSchedule.Type.ONCE) {
                String value = request.requiredText("at", 40);
                if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z")) {
                    throw RequestValidationException.invalid("schedule.at", "Expected a UTC timestamp ending in Z");
                }
                at = Instant.parse(value);
            } else {
                String value = request.requiredText("localTime", 5);
                if (!value.matches("\\d{2}:\\d{2}")) {
                    throw RequestValidationException.invalid("schedule.localTime", "Expected HH:mm");
                }
                time = LocalTime.parse(value);
                String date = request.requiredText("startDate", 10);
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw RequestValidationException.invalid("schedule.startDate", "Expected YYYY-MM-DD");
                }
                start = LocalDate.parse(date);
                if (type == ReminderSchedule.Type.WEEKLY) {
                    request.requiredText("dayOfWeek", 20);
                    day = request.nullableEnum("dayOfWeek", DayOfWeek.class);
                } else if (type == ReminderSchedule.Type.MONTHLY) {
                    long number = request.requiredNonNegativeLong("dayOfMonth");
                    if (number < 1 || number > 31) {
                        throw RequestValidationException.invalid("schedule.dayOfMonth", "Expected 1 to 31");
                    }
                    monthDay = (int) number;
                }
            }
        } catch (DateTimeParseException exception) {
            throw RequestValidationException.invalid("schedule", "Invalid date or time");
        }
        return new ReminderSchedule(type, at, zone, time, start, day, monthDay);
    }
}

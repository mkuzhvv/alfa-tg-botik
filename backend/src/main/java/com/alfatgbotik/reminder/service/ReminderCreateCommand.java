package com.alfatgbotik.reminder.service;

import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.reminder.model.ReminderSchedule;

import java.util.UUID;

public record ReminderCreateCommand(String title, ReminderSchedule schedule, UUID materialId, UUID planStepId) {

    public ReminderCreateCommand {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 160) {
            throw RequestValidationException.invalid("title", "Expected 1 to 160 characters");
        }
        if (schedule == null) {
            throw RequestValidationException.invalid("schedule", "Field is required");
        }
    }
}

package com.alfatgbotik.reminder.service;

import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;

public record ReminderUpdateCommand(long expectedVersion, String title, ReminderSchedule schedule, ReminderStatus status) {

    public ReminderUpdateCommand {
        if (expectedVersion < 0 || (title == null && schedule == null && status == null)) {
            throw RequestValidationException.invalid("request", "Expected a version and at least one change");
        }
        if (title != null && (title.isBlank() || title.codePointCount(0, title.length()) > 160)) {
            throw RequestValidationException.invalid("title", "Expected 1 to 160 characters");
        }
        if (status == ReminderStatus.COMPLETED) {
            throw RequestValidationException.invalid("status", "COMPLETED is assigned by the server");
        }
    }
}

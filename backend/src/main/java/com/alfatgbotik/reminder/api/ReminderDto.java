package com.alfatgbotik.reminder.api;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;

import java.time.Instant;
import java.util.UUID;

public record ReminderDto(UUID id, String title, ReminderStatus status, ReminderSchedule schedule,
                          Instant nextFireAt, Instant lastSentAt, String completionReason,
                          MaterialCardDto material, UUID planStepId, long version, Instant createdAt, Instant updatedAt) {
}

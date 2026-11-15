package com.alfatgbotik.reminder.api;

import com.alfatgbotik.reminder.persistence.ReminderRepository;
import com.alfatgbotik.reminder.service.ReminderException;
import com.alfatgbotik.reminder.service.ReminderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.UUID;

@Service
public class ReminderCreation {

    private final ReminderRepository reminders;
    private final ReminderService service;
    private final Clock clock;

    public ReminderCreation(ReminderRepository reminders, ReminderService service, Clock clock) {
        this.reminders = reminders;
        this.service = service;
        this.clock = clock;
    }

    @Transactional
    public ReminderDto create(UUID userId, UUID key, JsonNode body) {
        if (!reminders.tryLockCommand(userId, key)) {
            throw new ReminderException(409, "REQUEST_IN_PROGRESS");
        }
        var stored = reminders.findCommand(userId, key);
        if (stored.isPresent()) {
            if (!stored.get().request().equals(body)) {
                throw new ReminderException(409, "IDEMPOTENCY_CONFLICT");
            }
            return stored.get().response();
        }
        ReminderDto result = service.create(userId, ReminderRequests.create(body));
        reminders.saveCommand(userId, key, body, result, clock.instant());
        return result;
    }
}

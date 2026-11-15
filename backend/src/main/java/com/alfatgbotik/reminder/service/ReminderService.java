package com.alfatgbotik.reminder.service;

import com.alfatgbotik.catalog.service.MaterialService;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.identity.persistence.TelegramContactRepository;
import com.alfatgbotik.identity.service.UserNotFoundException;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.reminder.api.ReminderDto;
import com.alfatgbotik.reminder.api.ReminderPage;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.persistence.ReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ReminderService {

    private final ReminderRepository reminders;
    private final TelegramContactRepository contacts;
    private final MaterialService materials;
    private final PlanService plans;
    private final Clock clock;

    public ReminderService(ReminderRepository reminders, TelegramContactRepository contacts,
                           MaterialService materials, PlanService plans, Clock clock) {
        this.reminders = reminders;
        this.contacts = contacts;
        this.materials = materials;
        this.plans = plans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReminderDto getById(UUID userId, UUID reminderId) {
        return reminders.find(userId, reminderId).orElseThrow(() -> new ReminderException(404, "NOT_FOUND"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReminderPage list(UUID userId, ReminderStatus status, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw RequestValidationException.invalid("pagination", "Invalid offset or limit");
        }
        return new ReminderPage(reminders.list(userId, status, offset, limit), reminders.count(userId, status), offset, limit);
    }

    @Transactional
    public ReminderDto create(UUID userId, ReminderCreateCommand request) {
        var contact = contacts.lock(userId).orElseThrow(UserNotFoundException::new);
        requireContact(contact);
        Instant now = clock.instant();
        Instant next = request.schedule().requireFuture(now);
        if (request.planStepId() != null) {
            plans.requireReminderStep(userId, request.planStepId(), request.materialId());
        }
        String dataset = request.materialId() == null ? null : materials.requireActiveMaterials(Set.of(request.materialId()));
        UUID id = UUID.randomUUID();
        reminders.insert(id, userId, request.title(), request.schedule(), next, request.materialId(), dataset,
                request.planStepId(), now);
        return getById(userId, id);
    }

    @Transactional
    public ReminderDto update(UUID userId, UUID id, ReminderUpdateCommand command) {
        var contact = contacts.lock(userId).orElseThrow(UserNotFoundException::new);
        ReminderDto current = getById(userId, id);
        if (current.version() != command.expectedVersion()) {
            throw new ReminderException(409, "VERSION_CONFLICT");
        }
        if (current.status() == ReminderStatus.COMPLETED || current.status() == ReminderStatus.CANCELLED) {
            throw new ReminderException(409, "REMINDER_TERMINAL");
        }
        String title = command.title() == null ? current.title() : command.title();
        ReminderSchedule schedule = command.schedule() == null ? current.schedule() : command.schedule();
        ReminderStatus status = command.status() == null ? current.status() : command.status();
        boolean changedSchedule = !schedule.equals(current.schedule());
        boolean resuming = status == ReminderStatus.ACTIVE && current.status() == ReminderStatus.PAUSED;
        if (status == ReminderStatus.ACTIVE) {
            requireContact(contact);
        }
        Instant now = clock.instant();
        Instant next = current.nextFireAt();
        if (command.schedule() != null || resuming) {
            next = schedule.requireFuture(now);
        }
        if (status != ReminderStatus.ACTIVE) {
            next = null;
        }
        if (!title.equals(current.title()) || changedSchedule || status != current.status()
                || !Objects.equals(next, current.nextFireAt())) {
            reminders.update(current, title, schedule, status, next, current.lastSentAt(), null, now);
        }
        return getById(userId, id);
    }

    private void requireContact(TelegramContactRepository.Contact contact) {
        if (!contact.available()) {
            throw new ReminderException(422, "BOT_START_REQUIRED");
        }
    }
}

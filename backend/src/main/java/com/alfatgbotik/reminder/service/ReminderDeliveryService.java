package com.alfatgbotik.reminder.service;

import com.alfatgbotik.identity.persistence.TelegramContactRepository;
import com.alfatgbotik.reminder.api.ReminderDto;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.persistence.ReminderDeliveryRepository;
import com.alfatgbotik.reminder.persistence.ReminderDeliveryRepository.Delivery;
import com.alfatgbotik.reminder.persistence.ReminderRepository;
import com.alfatgbotik.telegram.TelegramReplySender;
import com.alfatgbotik.telegram.model.TelegramDeliveryResult;
import com.alfatgbotik.telegram.model.TelegramReply;
import com.alfatgbotik.telegram.model.TelegramUpdateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReminderDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDeliveryService.class);
    private static final Duration MAX_LATENESS = Duration.ofHours(24);
    private final ReminderRepository reminders;
    private final ReminderDeliveryRepository deliveries;
    private final TelegramContactRepository contacts;
    private final ObjectProvider<TelegramReplySender> sender;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReminderDeliveryService(ReminderRepository reminders, ReminderDeliveryRepository deliveries,
                                   TelegramContactRepository contacts, ObjectProvider<TelegramReplySender> sender,
                                   PlatformTransactionManager manager, Clock clock) {
        this.reminders = reminders;
        this.deliveries = deliveries;
        this.contacts = contacts;
        this.sender = sender;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public void recoverInterrupted() {
        for (var delivery : deliveries.interrupted()) {
            transactions.executeWithoutResult(status -> finish(delivery.userId(), delivery.reminderId(),
                    delivery.id(), TelegramDeliveryResult.unknown()));
        }
    }

    public void dispatchDue() {
        TelegramReplySender telegram = sender.getIfAvailable();
        if (telegram == null) {
            return;
        }
        recoverInterrupted();
        for (var due : reminders.due(clock.instant())) {
            try {
                UUID deliveryId = transactions.execute(status -> prepare(due.userId(), due.reminderId()));
                if (deliveryId == null) {
                    continue;
                }
                TelegramReply reply = transactions.execute(status -> beforeSend(due.userId(), due.reminderId(), deliveryId));
                if (reply == null) {
                    continue;
                }
                TelegramDeliveryResult result;
                try {
                    result = telegram.send(reply);
                } catch (RuntimeException exception) {
                    result = TelegramDeliveryResult.unknown();
                }
                TelegramDeliveryResult outcome = result;
                transactions.executeWithoutResult(status -> finish(due.userId(), due.reminderId(), deliveryId, outcome));
            } catch (RuntimeException exception) {
                log.error("Reminder delivery failed (id={}, type={})", due.reminderId(), exception.getClass().getSimpleName());
            }
        }
    }

    private UUID prepare(UUID userId, UUID reminderId) {
        var contact = contacts.lock(userId).orElseThrow();
        ReminderDto reminder = reminders.find(userId, reminderId).orElseThrow();
        Instant now = clock.instant();
        Delivery pending = deliveries.pending(reminderId).orElse(null);
        if (pending != null) {
            if (pending.status().equals("SENDING")) {
                return null;
            }
            if (reminder.status() != ReminderStatus.ACTIVE || reminder.version() != pending.version()) {
                deliveries.finish(pending.id(), "SKIPPED", null, null, now);
                return null;
            }
            return pending.retryAt().isAfter(now) ? null : pending.id();
        }
        if (reminder.status() != ReminderStatus.ACTIVE || reminder.nextFireAt().isAfter(now)) {
            return null;
        }
        if (!contact.available()) {
            reminders.pauseAll(userId, now);
            return null;
        }
        Instant scheduled = reminder.schedule().latestAtOrBefore(now);
        if (scheduled == null || scheduled.isBefore(now.minus(MAX_LATENESS))) {
            boolean once = reminder.schedule().type() == ReminderSchedule.Type.ONCE;
            reminders.update(reminder, reminder.title(), reminder.schedule(),
                    once ? ReminderStatus.COMPLETED : ReminderStatus.ACTIVE,
                    once ? null : reminder.schedule().nextAfter(now), reminder.lastSentAt(), once ? "EXPIRED" : null, now);
            return null;
        }
        return deliveries.insert(reminderId, reminder.version(), reminder.nextFireAt(), scheduled, now);
    }

    private TelegramReply beforeSend(UUID userId, UUID reminderId, UUID deliveryId) {
        var contact = contacts.lock(userId).orElseThrow();
        ReminderDto reminder = reminders.find(userId, reminderId).orElseThrow();
        Delivery delivery = deliveries.get(deliveryId);
        Instant now = clock.instant();
        if (!delivery.status().equals("PENDING")) {
            return null;
        }
        if (reminder.status() != ReminderStatus.ACTIVE || reminder.version() != delivery.version()) {
            deliveries.finish(deliveryId, "SKIPPED", null, null, now);
            return null;
        }
        if (!contact.available()) {
            deliveries.finish(deliveryId, "FAILED", "BOT_UNAVAILABLE", null, now);
            reminders.pauseAll(userId, now);
            return null;
        }
        if (delivery.scheduledAt().isBefore(now.minus(MAX_LATENESS))) {
            deliveries.finish(deliveryId, "SKIPPED", "EXPIRED", null, now);
            boolean once = reminder.schedule().type() == ReminderSchedule.Type.ONCE;
            reminders.update(reminder, reminder.title(), reminder.schedule(),
                    once ? ReminderStatus.COMPLETED : ReminderStatus.ACTIVE,
                    once ? null : reminder.schedule().nextAfter(now), reminder.lastSentAt(), once ? "EXPIRED" : null, now);
            return null;
        }
        deliveries.sending(deliveryId, now);
        String text = "Напоминание: " + reminder.title();
        if (reminder.material() != null) {
            text += "\n\n" + reminder.material().title() + "\n" + reminder.material().url();
        }
        if (delivery.scheduledAt().isBefore(now.minus(Duration.ofMinutes(1)))) {
            text += "\n\nОтправлено с опозданием.";
        }
        return TelegramReply.message(contact.chatId(), text, false);
    }

    private void finish(UUID userId, UUID reminderId, UUID deliveryId, TelegramDeliveryResult result) {
        contacts.lock(userId).orElseThrow();
        ReminderDto reminder = reminders.find(userId, reminderId).orElseThrow();
        Delivery delivery = deliveries.get(deliveryId);
        if (!delivery.status().equals("SENDING")) {
            return;
        }
        Instant now = clock.instant();
        boolean current = reminder.status() == ReminderStatus.ACTIVE && reminder.version() == delivery.version();
        boolean delivered = result.status() == TelegramUpdateStatus.DONE;
        boolean unknown = result.status() == TelegramUpdateStatus.DELIVERY_UNKNOWN;
        if ("API_403".equals(result.errorCode())) {
            deliveries.finish(deliveryId, "FAILED", result.errorCode(), null, now);
            contacts.disable(userId);
            reminders.pauseAll(userId, now);
            return;
        }
        if (result.status() == TelegramUpdateStatus.PENDING && current && delivery.attempts() < 3) {
            Instant retry = now.plus(result.retryAfter());
            if (!retry.isAfter(delivery.scheduledAt().plus(MAX_LATENESS))) {
                deliveries.finish(deliveryId, "PENDING", result.errorCode(), retry, now);
                return;
            }
        }
        deliveries.finish(deliveryId, delivered ? "DELIVERED" : unknown ? "UNKNOWN" : "FAILED",
                result.errorCode(), null, now);
        if (delivered || unknown) {
            Instant lastSent = delivered ? now : reminder.lastSentAt();
            boolean consume = reminder.status() == ReminderStatus.ACTIVE
                    && reminder.nextFireAt().equals(delivery.dueAt());
            boolean once = reminder.schedule().type() == ReminderSchedule.Type.ONCE;
            if (consume) {
                reminders.update(reminder, reminder.title(), reminder.schedule(),
                        once ? ReminderStatus.COMPLETED : ReminderStatus.ACTIVE,
                        once ? null : reminder.schedule().nextAfter(now), lastSent,
                        once ? delivered ? "DELIVERED" : "DELIVERY_UNKNOWN" : null, now);
            } else if (delivered) {
                reminders.update(reminder, reminder.title(), reminder.schedule(), reminder.status(),
                        reminder.nextFireAt(), lastSent, reminder.completionReason(), now);
            }
        } else if (current) {
            reminders.update(reminder, reminder.title(), reminder.schedule(), ReminderStatus.PAUSED,
                    null, reminder.lastSentAt(), null, now);
        }
        log.info("Reminder delivery recorded (id={}, outcome={})", reminderId,
                delivered ? "DELIVERED" : unknown ? "UNKNOWN" : "FAILED");
    }
}

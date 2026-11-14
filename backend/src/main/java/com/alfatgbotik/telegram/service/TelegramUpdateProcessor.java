package com.alfatgbotik.telegram.service;

import com.alfatgbotik.telegram.TelegramReplySender;
import com.alfatgbotik.telegram.TelegramUpdateHandler;
import com.alfatgbotik.telegram.model.StoredTelegramUpdate;
import com.alfatgbotik.telegram.model.TelegramDeliveryResult;
import com.alfatgbotik.telegram.model.TelegramUpdateStatus;
import com.alfatgbotik.telegram.persistence.TelegramUpdateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class TelegramUpdateProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateProcessor.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    private final TelegramUpdateRepository updates;
    private final TelegramUpdateHandler handler;
    private final TelegramReplySender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public TelegramUpdateProcessor(TelegramUpdateRepository updates, TelegramUpdateHandler handler,
                                   TelegramReplySender sender, PlatformTransactionManager transactionManager,
                                   Clock clock) {
        this.updates = updates;
        this.handler = handler;
        this.sender = sender;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void process(long botId, Update update) {
        StoredTelegramUpdate stored = transactions.execute(transaction -> {
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            if (updates.insertIfAbsent(id, botId, update.getUpdateId(), now)) {
                updates.saveReply(id, handler.prepare(update), clock.instant());
            }
            return updates.find(botId, update.getUpdateId());
        });
        deliver(stored);
    }

    public void retryPending(long botId) {
        recoverInterrupted(botId, clock.instant().minus(Duration.ofMinutes(1)));
        for (StoredTelegramUpdate update : updates.findDue(botId, clock.instant())) {
            deliver(update);
        }
    }

    public void recoverAfterRestart(long botId) {
        recoverInterrupted(botId, clock.instant());
    }

    private void deliver(StoredTelegramUpdate stored) {
        if (stored.status() != TelegramUpdateStatus.PENDING) {
            return;
        }
        StoredTelegramUpdate claimed = transactions.execute(transaction ->
                updates.claim(stored.id(), clock.instant()).orElse(null));
        if (claimed == null) {
            return;
        }

        TelegramDeliveryResult result = sender.send(claimed.reply());
        TelegramUpdateStatus status = result.status();
        if (status == TelegramUpdateStatus.PENDING && claimed.attempts() >= MAX_DELIVERY_ATTEMPTS) {
            status = TelegramUpdateStatus.FAILED;
        }
        Instant now = clock.instant();
        TelegramUpdateStatus finalStatus = status;
        transactions.executeWithoutResult(transaction -> updates.finish(claimed.id(), finalStatus,
                result.errorCode(), now.plus(result.retryAfter()), now));
        log.info("Telegram delivery recorded (bot_id={}, update_id={}, status={}, attempts={}, code={})",
                claimed.botId(), claimed.updateId(), status, claimed.attempts(), result.errorCode());
    }

    private void recoverInterrupted(long botId, Instant before) {
        Integer count = transactions.execute(transaction -> updates.recoverInterrupted(botId, before, clock.instant()));
        if (count != null && count > 0) {
            log.warn("Telegram interrupted deliveries marked unknown (bot_id={}, count={})", botId, count);
        }
    }
}

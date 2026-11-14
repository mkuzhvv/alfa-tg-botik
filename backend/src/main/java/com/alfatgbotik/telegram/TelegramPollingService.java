package com.alfatgbotik.telegram;

import com.alfatgbotik.telegram.service.TelegramUpdateProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.telegram.telegrambots.longpolling.interfaces.BackOff;
import org.telegram.telegrambots.longpolling.util.ExponentialBackOff;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Comparator;
import java.util.List;

@Order(2)
public class TelegramPollingService implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramClient telegramClient;
    private final TelegramUpdateProcessor processor;
    private final TelegramConnectionCheck connectionCheck;
    private volatile boolean running;
    private Thread pollingThread;
    private int nextOffset;

    public TelegramPollingService(TelegramClient telegramClient, TelegramUpdateProcessor processor,
                                  TelegramConnectionCheck connectionCheck) {
        this.telegramClient = telegramClient;
        this.processor = processor;
        this.connectionCheck = connectionCheck;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        processor.recoverAfterRestart(connectionCheck.getBotId());
        running = true;
        pollingThread = Thread.ofPlatform().name("telegram-polling").daemon(true).start(this::poll);
        log.info("Telegram polling started");
    }

    void pollOnce() throws TelegramApiException {
        long botId = connectionCheck.getBotId();
        processor.retryPending(botId);
        List<Update> updates = telegramClient.execute(GetUpdates.builder()
                .offset(nextOffset)
                .limit(100)
                .timeout(5)
                .allowedUpdates(List.of("message", "callback_query"))
                .build());
        for (Update update : updates.stream().sorted(Comparator.comparingInt(Update::getUpdateId)).toList()) {
            if (update.getUpdateId() < nextOffset) {
                continue;
            }
            processor.process(botId, update);
            nextOffset = update.getUpdateId() + 1;
        }
    }

    private void poll() {
        BackOff backOff = new ExponentialBackOff();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
                backOff.reset();
            } catch (Exception exception) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    return;
                }
                long delay = backOff.nextBackOffMillis();
                if (delay < 0) {
                    backOff.reset();
                    delay = 1000;
                }
                log.warn("Telegram polling or update handling failed; retrying in {} ms (type={})",
                        delay, exception.getClass().getSimpleName());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

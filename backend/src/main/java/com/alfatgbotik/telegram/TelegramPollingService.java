package com.alfatgbotik.telegram;

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

@Order(1)
public class TelegramPollingService implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramClient telegramClient;
    private final TelegramUpdateHandler updateHandler;
    private volatile boolean running;
    private Thread pollingThread;
    private int nextOffset;

    public TelegramPollingService(TelegramClient telegramClient, TelegramUpdateHandler updateHandler) {
        this.telegramClient = telegramClient;
        this.updateHandler = updateHandler;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        running = true;
        pollingThread = Thread.ofPlatform().name("telegram-polling").daemon(true).start(this::poll);
        log.info("Telegram polling started");
    }

    void pollOnce() throws TelegramApiException {
        List<Update> updates = telegramClient.execute(GetUpdates.builder()
                .offset(nextOffset)
                .limit(100)
                .timeout(5)
                .allowedUpdates(List.of("message"))
                .build());
        for (Update update : updates.stream().sorted(Comparator.comparingInt(Update::getUpdateId)).toList()) {
            if (update.getUpdateId() < nextOffset) {
                continue;
            }
            updateHandler.handle(update);
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

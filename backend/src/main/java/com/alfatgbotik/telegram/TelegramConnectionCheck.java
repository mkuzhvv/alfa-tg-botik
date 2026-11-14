package com.alfatgbotik.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Order(0)
public class TelegramConnectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramConnectionCheck.class);

    private final TelegramClient telegramClient;
    private long botId;

    public TelegramConnectionCheck(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        User bot;
        try {
            bot = telegramClient.execute(new GetMe());
        } catch (TelegramApiException exception) {
            throw new IllegalStateException(
                    "Telegram getMe failed. Check TELEGRAM_BOT_TOKEN and network access to api.telegram.org.");
        }
        if (bot == null || !Boolean.TRUE.equals(bot.getIsBot()) || bot.getId() == null || bot.getId() <= 0) {
            throw new IllegalStateException("Telegram getMe did not return a bot account");
        }
        botId = bot.getId();
        log.info("Telegram connected: @{} (id={})", bot.getUserName(), bot.getId());
    }

    public long getBotId() {
        if (botId == 0) {
            throw new IllegalStateException("Telegram bot identity has not been verified");
        }
        return botId;
    }
}

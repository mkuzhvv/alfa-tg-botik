package com.alfatgbotik.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Order(1)
public class TelegramCommandRegistration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandRegistration.class);

    private final TelegramClient telegramClient;

    public TelegramCommandRegistration(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            Boolean registered = telegramClient.execute(SetMyCommands.builder().commands(List.of(
                    BotCommand.builder().command("start").description("Начать работу").build(),
                    BotCommand.builder().command("help").description("Показать помощь").build(),
                    BotCommand.builder().command("profile").description("Профиль бизнеса").build(),
                    BotCommand.builder().command("plan").description("План и прогресс").build(),
                    BotCommand.builder().command("reminders").description("Управление напоминаниями").build()
            )).build());
            if (!Boolean.TRUE.equals(registered)) {
                throw new IllegalStateException("Telegram command registration was not confirmed");
            }
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Telegram command registration failed. Check network access to api.telegram.org.");
        }
        log.info("Telegram command menu registered");
    }
}

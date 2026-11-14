package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private static final String START_MESSAGE = """
            Привет! Добро пожаловать в помощник «Альфа-Курса».

            Консультации и рекомендации пока готовятся. Скоро здесь можно будет задать вопрос о своём бизнесе.
            """;

    private final UserService users;
    private final TelegramClient telegramClient;

    public TelegramUpdateHandler(UserService users, TelegramClient telegramClient) {
        this.users = users;
        this.telegramClient = telegramClient;
    }

    public void handle(Update update) throws TelegramApiException {
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        User sender = message.getFrom();
        if (message.getChat() == null || !"private".equals(message.getChat().getType())
                || sender == null || sender.getId() == null || sender.getId() <= 0
                || Boolean.TRUE.equals(sender.getIsBot()) || !message.hasText()) {
            return;
        }
        String command = message.getText().strip().split("\\s+", 2)[0];
        if (!"/start".equals(command)) {
            return;
        }
        String displayName = Stream.of(sender.getFirstName(), sender.getLastName())
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        users.confirmTelegramChat(sender.getId(), message.getChatId(),
                displayName.isEmpty() ? null : displayName);
        telegramClient.execute(SendMessage.builder()
                .chatId(message.getChatId().toString())
                .text(START_MESSAGE)
                .build());
        log.info("Telegram /start handled (update_id={})", update.getUpdateId());
    }
}

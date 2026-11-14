package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.telegram.model.TelegramReply;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TelegramUpdateHandler {

    private static final String START_MESSAGE = """
            Привет! Добро пожаловать в помощник «Альфа-Курса».

            Консультации и рекомендации пока готовятся. Скоро здесь можно будет задать вопрос о своём бизнесе.
            """;
    private static final String HELP_MESSAGE = """
            Доступные команды:
            /start — начать работу;
            /help — показать эту справку.

            Под приветствием есть кнопка «Помощь» — она открывает ту же справку.
            Консультации по материалам «Альфа-Курса» пока не подключены.
            """;
    private static final String CONSULTATIONS_PENDING_MESSAGE = """
            Консультации и рекомендации по материалам «Альфа-Курса» пока не подключены.

            Доступные команды: /start и /help.
            """;
    private static final String UNKNOWN_COMMAND_MESSAGE =
            "Неизвестная команда. Доступные команды: /start и /help.";
    private static final String UNSUPPORTED_MESSAGE =
            "Этот тип сообщения пока не поддерживается. Пришли текст или воспользуйся /help.";

    private final UserService users;

    public TelegramUpdateHandler(UserService users) {
        this.users = users;
    }

    public TelegramReply prepare(Update update) {
        if (update.hasCallbackQuery()) {
            return prepareCallback(update.getCallbackQuery());
        }
        if (update.hasMessage()) {
            return prepareMessage(update.getMessage());
        }
        return TelegramReply.none();
    }

    private TelegramReply prepareMessage(Message message) {
        User sender = message.getFrom();
        if (!isPrivateChat(message.getChat()) || !isHuman(sender)) {
            return TelegramReply.none();
        }
        if (!message.hasText()) {
            return TelegramReply.message(message.getChatId(), UNSUPPORTED_MESSAGE, false);
        }
        String text = message.getText().strip();
        String command = text.split("\\s+", 2)[0];
        return switch (command) {
            case "/start" -> prepareStart(message);
            case "/help" -> TelegramReply.message(message.getChatId(), HELP_MESSAGE, false);
            default -> TelegramReply.message(message.getChatId(), text.startsWith("/")
                    ? UNKNOWN_COMMAND_MESSAGE : CONSULTATIONS_PENDING_MESSAGE, false);
        };
    }

    private TelegramReply prepareStart(Message message) {
        User sender = message.getFrom();
        String displayName = Stream.of(sender.getFirstName(), sender.getLastName())
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        users.confirmTelegramChat(sender.getId(), message.getChatId(),
                displayName.isEmpty() ? null : displayName);
        return TelegramReply.message(message.getChatId(), START_MESSAGE, true);
    }

    private TelegramReply prepareCallback(CallbackQuery callback) {
        if (callback.getId() == null || callback.getId().isBlank() || !isHuman(callback.getFrom())) {
            return TelegramReply.none();
        }
        if (callback.getMessage() == null || !isPrivateChat(callback.getMessage().getChat())) {
            return TelegramReply.callback(callback.getId(), "Открой личный чат с ботом и отправь /help.", null, null);
        }
        if (!TelegramReply.HELP_ACTION.equals(callback.getData())) {
            return TelegramReply.callback(callback.getId(), "Эта кнопка больше не поддерживается. Отправь /help.", null, null);
        }
        return TelegramReply.callback(callback.getId(), null, callback.getMessage().getChatId(), HELP_MESSAGE);
    }

    private boolean isPrivateChat(Chat chat) {
        return chat != null && chat.getId() != null && chat.getId() > 0 && "private".equals(chat.getType());
    }

    private boolean isHuman(User sender) {
        return sender != null && sender.getId() != null && sender.getId() > 0
                && !Boolean.TRUE.equals(sender.getIsBot());
    }
}

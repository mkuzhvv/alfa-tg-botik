package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.assistant.service.ConversationService;
import com.alfatgbotik.telegram.model.TelegramReply;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;

public class TelegramUpdateHandler {

    private static final String START_MESSAGE = """
            Привет! Добро пожаловать в помощник «Альфа-Курса».

            Задай вопрос о своём бизнесе — помогу разобраться по материалам «Альфа-Курса» и подберу полезные ссылки.
            Подготовка ответа может занять до минуты.
            """;
    private static final String HELP_MESSAGE = """
            Доступные команды:
            /start — начать работу;
            /help — показать эту справку.

            Под приветствием есть кнопка «Помощь» — она открывает ту же справку.
            Для консультации просто отправь вопрос текстом, до 4000 символов.
            Я учитываю последние сообщения диалога. Подготовка ответа может занять до минуты.
            Сохранение планов и управление напоминаниями из переписки ещё не подключены.
            """;
    private static final String UNKNOWN_COMMAND_MESSAGE =
            "Неизвестная команда. Доступные команды: /start и /help.";
    private static final String UNSUPPORTED_MESSAGE =
            "Этот тип сообщения пока не поддерживается. Пришли текст или воспользуйся /help.";

    private final UserService users;
    private final ConversationService conversations;

    public TelegramUpdateHandler(UserService users, ConversationService conversations) {
        this.users = users;
        this.conversations = conversations;
    }

    public TelegramReply prepare(Update update, UUID requestId) {
        if (update.hasCallbackQuery()) {
            return prepareCallback(update.getCallbackQuery());
        }
        if (update.hasMessage()) {
            return prepareMessage(update.getMessage(), requestId);
        }
        return TelegramReply.none();
    }

    public boolean isConsultation(Update update) {
        if (!update.hasMessage()) {
            return false;
        }
        Message message = update.getMessage();
        return isPrivateChat(message.getChat()) && isHuman(message.getFrom()) && message.hasText()
                && !message.getText().strip().startsWith("/");
    }

    public void recoverAfterRestart() {
        conversations.recoverInterrupted();
    }

    private TelegramReply prepareMessage(Message message, UUID requestId) {
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
            default -> text.startsWith("/") ? TelegramReply.message(message.getChatId(), UNKNOWN_COMMAND_MESSAGE, false)
                    : prepareConsultation(message, requestId);
        };
    }

    private TelegramReply prepareStart(Message message) {
        User sender = message.getFrom();
        users.confirmTelegramChat(sender.getId(), message.getChatId(), displayName(sender));
        return TelegramReply.message(message.getChatId(), START_MESSAGE, true);
    }

    private TelegramReply prepareConsultation(Message message, UUID requestId) {
        String text = message.getText().strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 4000) {
            return TelegramReply.message(message.getChatId(), "Напиши непустой вопрос длиной до 4000 символов.", false);
        }
        var user = users.getOrCreate(message.getFrom().getId(), displayName(message.getFrom()));
        return TelegramReply.message(message.getChatId(), conversations.consult(user.id(), requestId, text), false);
    }

    private String displayName(User sender) {
        String displayName = Stream.of(sender.getFirstName(), sender.getLastName())
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        return displayName.isEmpty() ? null : displayName;
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

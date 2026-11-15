package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.assistant.service.ConversationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.InaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramUpdateHandlerTest {

    private final UserService users = mock(UserService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(users, conversations, mock(TelegramActions.class));

    @Test
    void registersPrivateChatAndPreparesWelcome() {
        Update update = messageUpdate(42, 99, "private", "/start campaign");
        update.getMessage().getFrom().setFirstName("Иван");
        update.getMessage().getFrom().setLastName("Петров");

        var reply = prepare(update);

        verify(users).confirmTelegramChat(42, 99, "Иван Петров");
        assertThat(reply.chatId()).isEqualTo(99);
        assertThat(reply.text()).contains("Альфа-Курса");
        assertThat(reply.helpButton()).isTrue();
    }

    @Test
    void ignoresEventsOutsidePrivateHumanChat() {
        assertThat(prepare(new Update()).isEmpty()).isTrue();
        assertThat(prepare(messageUpdate(42, -99, "group", "/start")).isEmpty()).isTrue();
        Update botSender = messageUpdate(42, 99, "private", "/start");
        botSender.getMessage().getFrom().setIsBot(true);
        assertThat(prepare(botSender).isEmpty()).isTrue();
        Update withoutSender = messageUpdate(42, 99, "private", "/help");
        withoutSender.getMessage().setFrom(null);
        assertThat(prepare(withoutSender).isEmpty()).isTrue();

        verifyNoInteractions(users);
    }

    @Test
    void doesNotPrepareWelcomeWhenRegistrationFails() {
        when(users.confirmTelegramChat(42, 99, "Test"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> prepare(messageUpdate(42, 99, "private", "/start")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "/help, Доступные команды",
            "/unknown, Неизвестная команда"
    })
    void routesTextWithoutChangingUserProfile(String text, String expectedReply) {
        var reply = prepare(messageUpdate(42, 99, "private", text));

        assertThat(reply.chatId()).isEqualTo(99);
        assertThat(reply.text()).contains(expectedReply);
        assertThat(reply.helpButton()).isFalse();
        verifyNoInteractions(users);
    }

    @Test
    void explainsUnsupportedMessageType() {
        var reply = prepare(messageUpdate(42, 99, "private", null));

        assertThat(reply.text()).contains("тип сообщения", "/help");
        verifyNoInteractions(users);
    }

    @Test
    void preparesAcknowledgementAndHelpForButton() {
        var reply = prepare(callbackUpdate("help"));

        assertThat(reply.callbackQueryId()).isEqualTo("callback-10");
        assertThat(reply.callbackText()).isNull();
        assertThat(reply.chatId()).isEqualTo(99);
        assertThat(reply.text()).contains("/start", "/help");
        verifyNoInteractions(users);
    }

    @Test
    void unknownButtonOnlyShowsNotice() {
        var reply = prepare(callbackUpdate("unknown-action"));

        assertThat(reply.callbackText()).contains("больше не поддерживается", "/help");
        assertThat(reply.chatId()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void callbackOutsidePrivateChatOnlyShowsNotice() {
        Update update = callbackUpdate("help");
        update.getCallbackQuery().setMessage(null);

        var reply = prepare(update);

        assertThat(reply.callbackText()).contains("личный чат");
        assertThat(reply.chatId()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void inaccessibleMessageStillAllowsHelp() {
        Update update = callbackUpdate("help");
        Chat chat = update.getCallbackQuery().getMessage().getChat();
        update.getCallbackQuery().setMessage(InaccessibleMessage.builder().chat(chat).messageId(1).date(0).build());

        assertThat(prepare(update).text()).contains("/help");
        verifyNoInteractions(users);
    }

    private com.alfatgbotik.telegram.model.TelegramReply prepare(Update update) {
        return handler.prepare(update, UUID.randomUUID());
    }

    private Update callbackUpdate(String data) {
        Message message = messageUpdate(42, 99, "private", "/start").getMessage();
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback-10");
        callback.setFrom(message.getFrom());
        callback.setMessage(message);
        callback.setData(data);
        Update update = new Update();
        update.setUpdateId(10);
        update.setCallbackQuery(callback);
        return update;
    }

    private Update messageUpdate(long userId, long chatId, String chatType, String text) {
        User sender = User.builder().id(userId).firstName("Test").isBot(false).build();
        Chat chat = Chat.builder().id(chatId).type(chatType).build();
        Message message = new Message();
        message.setFrom(sender);
        message.setChat(chat);
        message.setText(text);
        Update update = new Update();
        update.setUpdateId(10);
        update.setMessage(message);
        return update;
    }
}

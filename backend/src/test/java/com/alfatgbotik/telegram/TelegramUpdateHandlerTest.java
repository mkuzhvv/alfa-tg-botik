package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramUpdateHandlerTest {

    private final UserService users = mock(UserService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramUpdateHandler handler = new TelegramUpdateHandler(users, client);

    @Test
    void registersPrivateChatBeforeSendingWelcome() throws TelegramApiException {
        Update update = startUpdate(42, 99, "private", "/start campaign");
        update.getMessage().getFrom().setFirstName("Иван");
        update.getMessage().getFrom().setLastName("Петров");

        handler.handle(update);

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        InOrder order = inOrder(users, client);
        order.verify(users).confirmTelegramChat(42, 99, "Иван Петров");
        order.verify(client).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("99");
        assertThat(message.getValue().getText()).contains("Альфа-Курса");
    }

    @Test
    void ignoresEventsOutsidePrivateStartCommand() throws TelegramApiException {
        handler.handle(new Update());
        handler.handle(startUpdate(42, -99, "group", "/start"));
        handler.handle(startUpdate(42, 99, "private", "/starter"));
        Update nonText = startUpdate(42, 99, "private", null);
        handler.handle(nonText);
        Update botSender = startUpdate(42, 99, "private", "/start");
        botSender.getMessage().getFrom().setIsBot(true);
        handler.handle(botSender);

        verifyNoInteractions(users, client);
    }

    @Test
    void doesNotSendWelcomeWhenRegistrationFails() {
        when(users.confirmTelegramChat(42, 99, "Test"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> handler.handle(startUpdate(42, 99, "private", "/start")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(client);
    }

    private Update startUpdate(long userId, long chatId, String chatType, String text) {
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

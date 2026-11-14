package com.alfatgbotik.telegram;

import com.alfatgbotik.telegram.model.TelegramReply;
import com.alfatgbotik.telegram.model.TelegramUpdateStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramReplySenderTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramReplySender sender = new TelegramReplySender(client);

    @Test
    void sendsWelcomeWithHelpButton() throws Exception {
        var result = sender.send(TelegramReply.message(99, "Привет!", true));

        ArgumentCaptor<SendMessage> request = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(request.capture());
        assertThat(result.status()).isEqualTo(TelegramUpdateStatus.DONE);
        assertThat(request.getValue().getChatId()).isEqualTo("99");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) request.getValue().getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData()).isEqualTo("help");
    }

    @Test
    void acknowledgesCallbackBeforeSendingText() throws Exception {
        sender.send(TelegramReply.callback("callback-10", null, 99L, "Помощь"));

        var order = inOrder(client);
        order.verify(client).execute(any(AnswerCallbackQuery.class));
        order.verify(client).execute(any(SendMessage.class));
    }

    @Test
    void expiredCallbackDoesNotPreventHelpMessage() throws Exception {
        when(client.execute(any(AnswerCallbackQuery.class))).thenThrow(rejection(400));

        var result = sender.send(TelegramReply.callback("callback-10", null, 99L, "Помощь"));

        assertThat(result.status()).isEqualTo(TelegramUpdateStatus.DONE);
        verify(client).execute(any(SendMessage.class));
    }

    @Test
    void callbackTransportFailureCanRetryBeforeSendingChatMessage() throws Exception {
        when(client.execute(any(AnswerCallbackQuery.class))).thenThrow(new TelegramApiException("private-details"));

        var result = sender.send(TelegramReply.callback("callback-10", null, 99L, "Помощь"));

        assertThat(result.status()).isEqualTo(TelegramUpdateStatus.PENDING);
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void temporaryApiRejectionCanRetry() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(rejection(502));

        assertThat(sender.send(TelegramReply.message(99, "test", false)).status())
                .isEqualTo(TelegramUpdateStatus.PENDING);
    }

    @Test
    void permanentApiRejectionDoesNotRetry() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(rejection(403));

        assertThat(sender.send(TelegramReply.message(99, "test", false)).status())
                .isEqualTo(TelegramUpdateStatus.FAILED);
    }

    @Test
    void ambiguousMessageDeliveryDoesNotAutomaticallyRetry() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("private-details"));

        assertThat(sender.send(TelegramReply.message(99, "test", false)).status())
                .isEqualTo(TelegramUpdateStatus.DELIVERY_UNKNOWN);
    }

    private TelegramApiRequestException rejection(int code) {
        return new TelegramApiRequestException("private-details", ApiResponse.<Boolean>builder()
                .ok(false).errorCode(code).errorDescription("private-details").build());
    }
}

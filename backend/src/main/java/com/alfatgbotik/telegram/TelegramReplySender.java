package com.alfatgbotik.telegram;

import com.alfatgbotik.telegram.model.TelegramDeliveryResult;
import com.alfatgbotik.telegram.model.TelegramReply;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.util.List;

public class TelegramReplySender {

    private final TelegramClient telegramClient;

    public TelegramReplySender(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    public TelegramDeliveryResult send(TelegramReply reply) {
        if (reply.callbackQueryId() != null) {
            try {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(reply.callbackQueryId()).text(reply.callbackText()).build());
            } catch (TelegramApiRequestException exception) {
                if (!Integer.valueOf(400).equals(exception.getErrorCode())) {
                    return rejection(exception);
                }
            } catch (TelegramApiException exception) {
                return TelegramDeliveryResult.retry("CALLBACK_TRANSPORT", Duration.ofSeconds(2));
            }
        }
        if (reply.chatId() == null) {
            return TelegramDeliveryResult.delivered();
        }
        try {
            telegramClient.execute(SendMessage.builder().chatId(reply.chatId().toString()).text(reply.text())
                    .replyMarkup(!reply.buttons().isEmpty() ? keyboard(reply.buttons()) : reply.helpButton() ? helpKeyboard() : null).build());
            return TelegramDeliveryResult.delivered();
        } catch (TelegramApiRequestException exception) {
            return rejection(exception);
        } catch (TelegramApiException exception) {
            return TelegramDeliveryResult.unknown();
        }
    }

    private TelegramDeliveryResult rejection(TelegramApiRequestException exception) {
        Integer code = exception.getErrorCode();
        String errorCode = code == null ? "API_UNKNOWN" : "API_" + code;
        if (Integer.valueOf(429).equals(code) || (code != null && code >= 500)) {
            Integer retryAfter = exception.getParameters() == null ? null : exception.getParameters().getRetryAfter();
            return TelegramDeliveryResult.retry(errorCode,
                    Duration.ofSeconds(retryAfter == null ? 2 : Math.max(1, retryAfter)));
        }
        if (code == null || code == 0) {
            return TelegramDeliveryResult.unknown();
        }
        return TelegramDeliveryResult.failed(errorCode);
    }

    private InlineKeyboardMarkup helpKeyboard() {
        InlineKeyboardButton button = InlineKeyboardButton.builder()
                .text("Помощь").callbackData(TelegramReply.HELP_ACTION).build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(button))).build();
    }

    private InlineKeyboardMarkup keyboard(List<TelegramReply.Button> buttons) {
        return InlineKeyboardMarkup.builder().keyboard(buttons.stream().map(button -> new InlineKeyboardRow(
                InlineKeyboardButton.builder().text(button.text()).callbackData(button.callbackData()).url(button.url()).build())).toList()).build();
    }
}

package com.alfatgbotik.telegram.model;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record TelegramReply(Long chatId, String text, boolean helpButton,
                            String callbackQueryId, String callbackText, List<Button> buttons) {

    public TelegramReply {
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    public TelegramReply(Long chatId, String text, boolean helpButton, String callbackQueryId, String callbackText) {
        this(chatId, text, helpButton, callbackQueryId, callbackText, List.of());
    }

    public TelegramReply withButtons(List<Button> buttons) {
        return new TelegramReply(chatId, text, helpButton, callbackQueryId, callbackText, buttons);
    }

    public record Button(String text, String callbackData, String url) {
        public Button {
            if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 64
                    || (callbackData == null) == (url == null)
                    || (callbackData != null && (callbackData.isBlank() || callbackData.getBytes(StandardCharsets.UTF_8).length > 64))
                    || (url != null && (!url.startsWith("https://") || url.length() > 2000))) {
                throw new IllegalArgumentException("Invalid Telegram button");
            }
        }

        public static Button action(String text, String callback) { return new Button(text, callback, null); }
        public static Button link(String text, String url) { return new Button(text, null, url); }
    }

    public static final String HELP_ACTION = "help";

    public static TelegramReply none() {
        return new TelegramReply(null, null, false, null, null);
    }

    public static TelegramReply message(long chatId, String text, boolean helpButton) {
        return new TelegramReply(chatId, text, helpButton, null, null);
    }

    public static TelegramReply callback(String callbackId, String notice, Long chatId, String text) {
        return new TelegramReply(chatId, text, false, callbackId, notice);
    }

    public boolean isEmpty() {
        return chatId == null && callbackQueryId == null;
    }
}

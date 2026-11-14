package com.alfatgbotik.telegram.model;

public record TelegramReply(Long chatId, String text, boolean helpButton,
                            String callbackQueryId, String callbackText) {

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

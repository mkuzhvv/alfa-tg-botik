package com.alfatgbotik.telegram;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.net.URI;

@Order(1)
public class TelegramMiniAppMenu implements ApplicationRunner {

    private final TelegramClient client;
    private final String url;

    public TelegramMiniAppMenu(TelegramClient client, String url) {
        this.client = client;
        this.url = url;
        if (!url.isBlank()) {
            URI address = URI.create(url);
            if (!"https".equals(address.getScheme()) || address.getHost() == null
                    || address.getUserInfo() != null || address.getFragment() != null) {
                throw new IllegalArgumentException("TELEGRAM_MINI_APP_URL must be an HTTPS URL");
            }
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (url.isBlank()) return;
        try {
            Boolean saved = client.execute(SetChatMenuButton.builder().menuButton(MenuButtonWebApp.builder()
                    .text("Мой бизнес").webAppInfo(WebAppInfo.builder().url(url).build()).build()).build());
            if (!Boolean.TRUE.equals(saved)) throw new IllegalStateException("Telegram Mini App menu was not confirmed");
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Telegram Mini App menu registration failed");
        }
    }
}

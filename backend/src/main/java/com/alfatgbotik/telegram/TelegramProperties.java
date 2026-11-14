package com.alfatgbotik.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public final class TelegramProperties {

    private final boolean enabled;
    private final String token;

    public TelegramProperties(boolean enabled, String token) {
        if (enabled && (token == null || token.isBlank())) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN must be set when Telegram is enabled");
        }
        this.enabled = enabled;
        this.token = token;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getToken() {
        return token;
    }
}

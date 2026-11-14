package com.alfatgbotik.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TelegramConfiguration.class);

    @Test
    void telegramIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TelegramClient.class);
            assertThat(context).doesNotHaveBean(TelegramConnectionCheck.class);
        });
    }

    @Test
    void enabledTelegramRequiresToken() {
        contextRunner.withPropertyValues("telegram.enabled=true", "telegram.token=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage(
                                "TELEGRAM_BOT_TOKEN must be set when Telegram is enabled"));
    }

    @Test
    void enabledTelegramCreatesClient() {
        contextRunner.withPropertyValues("telegram.enabled=true", "telegram.token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TelegramClient.class);
                    assertThat(context).hasSingleBean(TelegramConnectionCheck.class);
                });
    }

    @Test
    void connectionFailureDoesNotExposeClientException() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(GetMe.class))).thenThrow(new TelegramApiException("private-request-details"));

        assertThatThrownBy(() -> new TelegramConnectionCheck(client).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Telegram getMe failed. Check TELEGRAM_BOT_TOKEN and network access to api.telegram.org.")
                .hasNoCause();
    }
}

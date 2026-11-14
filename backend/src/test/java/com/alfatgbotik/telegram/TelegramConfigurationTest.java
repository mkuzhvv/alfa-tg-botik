package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.telegram.persistence.TelegramUpdateRepository;
import org.springframework.transaction.PlatformTransactionManager;
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
            .withBean(UserService.class, () -> mock(UserService.class))
            .withBean(TelegramUpdateRepository.class, () -> mock(TelegramUpdateRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(TelegramConfiguration.class);

    @Test
    void telegramIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TelegramClient.class);
            assertThat(context).doesNotHaveBean(TelegramConnectionCheck.class);
            assertThat(context).doesNotHaveBean(TelegramCommandRegistration.class);
            assertThat(context).doesNotHaveBean(TelegramPollingService.class);
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
                    assertThat(context).hasSingleBean(TelegramCommandRegistration.class);
                    assertThat(context).hasSingleBean(TelegramPollingService.class);
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

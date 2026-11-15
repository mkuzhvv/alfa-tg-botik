package com.alfatgbotik.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramMiniAppMenuTest {
    @Test
    void leavesMenuUnchangedWithoutConfiguredUrl() {
        var client = mock(TelegramClient.class);
        new TelegramMiniAppMenu(client, "").run(null);
        verifyNoInteractions(client);
    }

    @Test
    void registersHttpsMiniAppWithoutChangingCommands() throws Exception {
        var client = mock(TelegramClient.class);
        when(client.execute(any(SetChatMenuButton.class))).thenAnswer(invocation -> {
            SetChatMenuButton request = invocation.getArgument(0);
            var button = (MenuButtonWebApp) request.getMenuButton();
            assertThat(button.getText()).isEqualTo("Мой бизнес");
            assertThat(button.getWebAppInfo().getUrl()).isEqualTo("https://example.test");
            return true;
        });
        new TelegramMiniAppMenu(client, "https://example.test").run(null);
        verify(client).execute(any(SetChatMenuButton.class));
        assertThatThrownBy(() -> new TelegramMiniAppMenu(client, "http://example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

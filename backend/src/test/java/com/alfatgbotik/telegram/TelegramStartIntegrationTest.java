package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.persistence.AppUserRepository;
import com.alfatgbotik.identity.persistence.BusinessProfileRepository;
import com.alfatgbotik.identity.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "telegram.enabled=false")
class TelegramStartIntegrationTest {

    @Autowired
    private UserService users;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private BusinessProfileRepository profileRepository;

    private final long telegramUserId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - 1);

    @AfterEach
    void deleteTestUser() {
        userRepository.findByTelegramUserId(telegramUserId).ifPresent(userRepository::delete);
    }

    @Test
    void repeatedStartPersistsOneUserAndSendsAfterCommit() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(userRepository.findByTelegramUserId(telegramUserId)).isPresent();
            return null;
        });
        TelegramUpdateHandler handler = new TelegramUpdateHandler(users, client);
        User sender = User.builder().id(telegramUserId).firstName("Test").isBot(false).build();
        Chat chat = Chat.builder().id(telegramUserId + 1).type("private").build();
        Message message = new Message();
        message.setFrom(sender);
        message.setChat(chat);
        message.setText("/start");
        Update update = new Update();
        update.setUpdateId(10);
        update.setMessage(message);

        handler.handle(update);
        handler.handle(update);

        var user = userRepository.findByTelegramUserId(telegramUserId).orElseThrow();
        assertThat(userRepository.countByTelegramUserId(telegramUserId)).isEqualTo(1);
        assertThat(user.getTelegramChatId()).isEqualTo(telegramUserId + 1);
        assertThat(user.canReceiveReminders()).isTrue();
        assertThat(profileRepository.findById(user.getId())).isPresent();
        assertThat(users.getById(user.getId()).profileVersion()).isZero();
    }
}

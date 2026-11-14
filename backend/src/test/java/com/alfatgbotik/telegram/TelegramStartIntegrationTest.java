package com.alfatgbotik.telegram;

import com.alfatgbotik.identity.persistence.AppUserRepository;
import com.alfatgbotik.identity.persistence.BusinessProfileRepository;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.telegram.model.TelegramUpdateStatus;
import com.alfatgbotik.telegram.persistence.TelegramUpdateRepository;
import com.alfatgbotik.telegram.service.TelegramUpdateProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "telegram.enabled=false")
class TelegramStartIntegrationTest {

    @Autowired
    private UserService users;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private BusinessProfileRepository profileRepository;
    @Autowired
    private TelegramUpdateRepository updates;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private final long botId = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 51);
    private final long telegramUserId = ThreadLocalRandom.current().nextLong(1L << 50, 1L << 51);
    private final TelegramClient client = mock(TelegramClient.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private TelegramUpdateHandler handler;
    private TelegramUpdateProcessor processor;

    @BeforeEach
    void configureProcessor() {
        when(clock.instant()).thenReturn(now);
        handler = spy(new TelegramUpdateHandler(users));
        processor = newProcessor();
    }

    @AfterEach
    void deleteTestData() {
        jdbc.update("DELETE FROM app.telegram_updates WHERE bot_id = ?", botId);
        userRepository.findByTelegramUserId(telegramUserId).ifPresent(userRepository::delete);
    }

    @Test
    void duplicateAfterProcessorRestartDoesNotRepeatBusinessActionOrReply() throws Exception {
        when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(userRepository.findByTelegramUserId(telegramUserId)).isPresent();
            return new Message();
        });
        Update update = messageUpdate(10, "/start");

        processor.process(botId, update);
        newProcessor().process(botId, update);

        verify(handler).prepare(update);
        verify(client).execute(any(SendMessage.class));
        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.DONE);
        var user = userRepository.findByTelegramUserId(telegramUserId).orElseThrow();
        assertThat(userRepository.countByTelegramUserId(telegramUserId)).isEqualTo(1);
        assertThat(user.getTelegramChatId()).isEqualTo(telegramUserId);
        assertThat(user.canReceiveReminders()).isTrue();
        assertThat(profileRepository.findById(user.getId())).isPresent();
        assertThat(users.getById(user.getId()).profileVersion()).isZero();
    }

    @Test
    void preparationFailureRollsBackEventAndJpaUserTogether() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated preparation failure");
        }).when(handler).prepare(any(Update.class));

        assertThatThrownBy(() -> processor.process(botId, messageUpdate(10, "/start")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.findByTelegramUserId(telegramUserId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.telegram_updates WHERE bot_id = ?",
                Long.class, botId)).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void rateLimitRetriesStoredReplyAfterRequestedDelayWithoutRepeatingRegistration() throws Exception {
        ResponseParameters parameters = mock(ResponseParameters.class);
        when(parameters.getRetryAfter()).thenReturn(30);
        when(client.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiRequestException("private-details", ApiResponse.<Message>builder()
                        .ok(false).errorCode(429).parameters(parameters).build()))
                .thenReturn(new Message());
        Update update = messageUpdate(10, "/start");

        processor.process(botId, update);
        newProcessor().process(botId, update);
        processor.retryPending(botId);
        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.PENDING);
        assertThat(updates.find(botId, 10).nextAttemptAt()).isEqualTo(now.plusSeconds(30));
        verify(client).execute(any(SendMessage.class));

        when(clock.instant()).thenReturn(now.plusSeconds(31));
        newProcessor().retryPending(botId);

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.DONE);
        assertThat(updates.find(botId, 10).attempts()).isEqualTo(2);
        verify(client, times(2)).execute(any(SendMessage.class));
        verify(handler).prepare(update);
    }

    @Test
    void permanentDeliveryFailureDoesNotBlockNextUpdate() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(apiError(403)).thenReturn(new Message());

        processor.process(botId, messageUpdate(10, "/start"));
        processor.process(botId, messageUpdate(11, "/help"));

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.FAILED);
        assertThat(updates.find(botId, 11).status()).isEqualTo(TelegramUpdateStatus.DONE);
        assertThat(userRepository.findByTelegramUserId(telegramUserId)).isPresent();
    }

    @Test
    void retryLimitStopsPermanentlyFailingDelivery() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(apiError(502));
        processor.process(botId, messageUpdate(10, "/help"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            when(clock.instant()).thenReturn(now.plusSeconds(attempt * 3L));
            processor.retryPending(botId);
        }

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.FAILED);
        assertThat(updates.find(botId, 10).attempts()).isEqualTo(3);
        verify(client, times(3)).execute(any(SendMessage.class));
    }

    @Test
    void ambiguousTransportFailureIsNotResentOnDuplicateOrRestart() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("private-details"));
        Update update = messageUpdate(10, "/start");

        processor.process(botId, update);
        newProcessor().recoverAfterRestart(botId);
        newProcessor().process(botId, update);
        processor.retryPending(botId);

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.DELIVERY_UNKNOWN);
        verify(client).execute(any(SendMessage.class));
        verify(handler).prepare(update);
    }

    @Test
    void interruptedSendingIsRecoveredWithoutDuplicateSend() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(new AssertionError("simulated process crash"));
        Update update = messageUpdate(10, "/start");
        assertThatThrownBy(() -> processor.process(botId, update)).isInstanceOf(AssertionError.class);
        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.SENDING);

        TelegramUpdateProcessor restarted = newProcessor();
        restarted.recoverAfterRestart(botId);
        restarted.process(botId, update);
        restarted.retryPending(botId);

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.DELIVERY_UNKNOWN);
        verify(client).execute(any(SendMessage.class));
        verify(handler).prepare(update);
    }

    @Test
    void groupUpdateIsRecordedAsIgnoredWithoutRegistrationOrNetworkCalls() {
        Update update = messageUpdate(10, "/start");
        update.getMessage().setChat(Chat.builder().id(-99L).type("group").build());

        processor.process(botId, update);
        processor.process(botId, update);

        assertThat(updates.find(botId, 10).status()).isEqualTo(TelegramUpdateStatus.IGNORED);
        assertThat(userRepository.findByTelegramUserId(telegramUserId)).isEmpty();
        verifyNoInteractions(client);
        verify(handler).prepare(update);
    }

    private TelegramUpdateProcessor newProcessor() {
        return new TelegramUpdateProcessor(updates, handler, new TelegramReplySender(client),
                transactionManager, clock);
    }

    private TelegramApiRequestException apiError(int code) {
        return new TelegramApiRequestException("private-details",
                ApiResponse.<Message>builder().ok(false).errorCode(code).build());
    }

    private Update messageUpdate(int id, String text) {
        Message message = new Message();
        message.setFrom(User.builder().id(telegramUserId).firstName("Test").isBot(false).build());
        message.setChat(Chat.builder().id(telegramUserId).type("private").build());
        message.setText(text);
        Update update = new Update();
        update.setUpdateId(id);
        update.setMessage(message);
        return update;
    }
}

package com.alfatgbotik.telegram;

import com.alfatgbotik.telegram.service.TelegramUpdateProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramPollingServiceTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramUpdateProcessor processor = mock(TelegramUpdateProcessor.class);
    private final TelegramConnectionCheck connection = mock(TelegramConnectionCheck.class);
    private final TelegramPollingService polling = new TelegramPollingService(client, processor, connection);

    TelegramPollingServiceTest() {
        when(connection.getBotId()).thenReturn(123L);
    }

    @Test
    void processesUpdatesInOrderAndAdvancesOffsetAfterSuccess() throws TelegramApiException {
        Update first = update(10);
        Update second = update(11);
        when(client.execute(any(GetUpdates.class)))
                .thenReturn(new ArrayList<>(List.of(second, first)))
                .thenReturn(new ArrayList<>());

        polling.pollOnce();
        polling.pollOnce();

        var order = inOrder(processor);
        order.verify(processor).retryPending(123);
        order.verify(processor).process(123, first);
        order.verify(processor).process(123, second);
        ArgumentCaptor<GetUpdates> requests = ArgumentCaptor.forClass(GetUpdates.class);
        verify(client, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetUpdates::getOffset).containsExactly(0, 12);
        assertThat(requests.getAllValues().getFirst().getAllowedUpdates())
                .containsExactly("message", "callback_query");
    }

    @Test
    void retriesDatabaseFailureWithoutAcknowledgingUpdate() throws TelegramApiException {
        Update update = update(10);
        when(client.execute(any(GetUpdates.class))).thenReturn(new ArrayList<>(List.of(update)));
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doNothing().when(processor).process(123, update);

        assertThatThrownBy(polling::pollOnce).isInstanceOf(DataAccessResourceFailureException.class);
        polling.pollOnce();

        ArgumentCaptor<GetUpdates> requests = ArgumentCaptor.forClass(GetUpdates.class);
        verify(client, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetUpdates::getOffset).containsExactly(0, 0);
    }

    @Test
    void retriesOnlyUnfinishedPartOfBatchAfterProcessingFailure() throws TelegramApiException {
        Update first = update(10);
        Update second = update(11);
        when(client.execute(any(GetUpdates.class)))
                .thenReturn(new ArrayList<>(List.of(first, second)))
                .thenReturn(new ArrayList<>(List.of(second)));
        doThrow(new DataAccessResourceFailureException("record failed"))
                .doNothing().when(processor).process(123, second);

        assertThatThrownBy(polling::pollOnce).isInstanceOf(DataAccessResourceFailureException.class);
        polling.pollOnce();

        verify(processor).process(123, first);
        ArgumentCaptor<GetUpdates> requests = ArgumentCaptor.forClass(GetUpdates.class);
        verify(client, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetUpdates::getOffset).containsExactly(0, 11);
    }

    @Test
    void shutdownInterruptsWaitingPollingThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return new ArrayList<Update>();
            } finally {
                exited.countDown();
            }
        });
        try {
            polling.run(null);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            polling.close();
        }
        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        verify(processor).recoverAfterRestart(123);
    }

    private Update update(int id) {
        Update update = new Update();
        update.setUpdateId(id);
        return update;
    }
}

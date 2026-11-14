package com.alfatgbotik.telegram;

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
    private final TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
    private final TelegramPollingService polling = new TelegramPollingService(client, handler);

    @Test
    void processesUpdatesInOrderAndAdvancesOffsetAfterSuccess() throws TelegramApiException {
        Update first = update(10);
        Update second = update(11);
        when(client.execute(any(GetUpdates.class)))
                .thenReturn(new ArrayList<>(List.of(second, first)))
                .thenReturn(new ArrayList<>());

        polling.pollOnce();
        polling.pollOnce();

        var order = inOrder(handler);
        order.verify(handler).handle(first);
        order.verify(handler).handle(second);
        ArgumentCaptor<GetUpdates> requests = ArgumentCaptor.forClass(GetUpdates.class);
        verify(client, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetUpdates::getOffset).containsExactly(0, 12);
    }

    @Test
    void retriesDatabaseFailureWithoutAcknowledgingUpdate() throws TelegramApiException {
        Update update = update(10);
        when(client.execute(any(GetUpdates.class))).thenReturn(new ArrayList<>(List.of(update)));
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doNothing().when(handler).handle(update);

        assertThatThrownBy(polling::pollOnce).isInstanceOf(DataAccessResourceFailureException.class);
        polling.pollOnce();

        ArgumentCaptor<GetUpdates> requests = ArgumentCaptor.forClass(GetUpdates.class);
        verify(client, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetUpdates::getOffset).containsExactly(0, 0);
    }

    @Test
    void retriesOnlyUnfinishedPartOfBatchAfterSendFailure() throws TelegramApiException {
        Update first = update(10);
        Update second = update(11);
        when(client.execute(any(GetUpdates.class)))
                .thenReturn(new ArrayList<>(List.of(first, second)))
                .thenReturn(new ArrayList<>(List.of(second)));
        doThrow(new TelegramApiException("send failed")).doNothing().when(handler).handle(second);

        assertThatThrownBy(polling::pollOnce).isInstanceOf(TelegramApiException.class);
        polling.pollOnce();

        verify(handler).handle(first);
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
    }

    private Update update(int id) {
        Update update = new Update();
        update.setUpdateId(id);
        return update;
    }
}

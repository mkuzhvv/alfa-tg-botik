package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.ConsultationDeadline;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class AssistantExecution {

    private final Duration timeout;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("assistant-model-", 0).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public AssistantExecution(@Value("${assistant.timeout:60s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Assistant timeout must be between 0 and 60 seconds");
        }
        this.timeout = timeout;
    }

    public ConsultationDeadline deadline() {
        return new ConsultationDeadline(timeout);
    }

    public <T> T run(ConsultationDeadline deadline, Callable<T> operation) {
        Future<T> future;
        try {
            future = executor.submit(operation);
        } catch (RejectedExecutionException exception) {
            throw new AssistantFailure("BUSY");
        }
        try {
            return future.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (Exception exception) {
            future.cancel(true);
            executor.purge();
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            if (cause instanceof AssistantFailure failure) {
                throw failure;
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssistantFailure(cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof InterruptedException ? "TIMEOUT" : "INTERNAL_ERROR");
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}

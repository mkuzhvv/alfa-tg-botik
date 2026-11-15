package com.alfatgbotik.assistant.model;

import com.alfatgbotik.assistant.service.AssistantFailure;

import java.time.Duration;

public final class ConsultationDeadline {

    private final long expiresAt;

    public ConsultationDeadline(Duration budget) {
        expiresAt = System.nanoTime() + budget.toNanos();
    }

    public Duration remaining() {
        long remaining = expiresAt - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw new AssistantFailure("TIMEOUT");
        }
        return Duration.ofNanos(remaining);
    }
}

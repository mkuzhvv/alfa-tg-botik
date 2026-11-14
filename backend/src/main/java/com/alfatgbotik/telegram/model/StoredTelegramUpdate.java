package com.alfatgbotik.telegram.model;

import java.time.Instant;
import java.util.UUID;

public record StoredTelegramUpdate(UUID id, long botId, int updateId, TelegramReply reply,
                                   TelegramUpdateStatus status, int attempts, Instant nextAttemptAt) {
}

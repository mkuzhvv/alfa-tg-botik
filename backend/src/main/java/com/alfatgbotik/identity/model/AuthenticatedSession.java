package com.alfatgbotik.identity.model;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedSession(UUID userId, String tokenHash, Instant expiresAt) {
}

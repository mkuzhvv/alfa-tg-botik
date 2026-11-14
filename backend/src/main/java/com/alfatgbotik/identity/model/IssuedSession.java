package com.alfatgbotik.identity.model;

import java.time.Instant;

public record IssuedSession(String accessToken, Instant expiresAt) {
}

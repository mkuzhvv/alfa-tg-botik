package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.identity.model.IssuedSession;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Duration SESSION_DURATION = Duration.ofHours(8);
    private final Map<String, AuthenticatedSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public SessionService(Clock clock) {
        this.clock = clock;
    }

    public IssuedSession issue(UUID userId) {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> !session.expiresAt().isAfter(now));
        Instant expiresAt = now.plus(SESSION_DURATION);
        while (true) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String hash = hash(token);
            if (sessions.putIfAbsent(hash, new AuthenticatedSession(userId, hash, expiresAt)) == null) {
                return new IssuedSession(token, expiresAt);
            }
        }
    }

    public Optional<AuthenticatedSession> find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        String hash = hash(token);
        AuthenticatedSession session = sessions.get(hash);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(hash, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void revoke(AuthenticatedSession session) {
        sessions.remove(session.tokenHash(), session);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}

package com.alfatgbotik.identity.auth;

import com.alfatgbotik.identity.service.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private final Clock clock = mock(Clock.class);

    @Test
    void createsIndependentSessionsAndRevokesOnlyCurrentOne() {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        SessionService service = new SessionService(clock);
        UUID userId = UUID.randomUUID();

        var first = service.issue(userId);
        var second = service.issue(userId);
        var current = service.find(first.accessToken()).orElseThrow();

        assertThat(first.accessToken()).hasSize(43).isNotEqualTo(second.accessToken());
        assertThat(current.userId()).isEqualTo(userId);
        assertThat(current.tokenHash()).isNotEqualTo(first.accessToken());
        assertThat(first.expiresAt()).isEqualTo(now.plusSeconds(8 * 3600));
        service.revoke(current);
        assertThat(service.find(first.accessToken())).isEmpty();
        assertThat(service.find(second.accessToken())).isPresent();
    }

    @Test
    void expiresExactlyAtDeadlineAndDoesNotSurviveNewServiceInstance() {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        SessionService service = new SessionService(clock);
        var session = service.issue(UUID.randomUUID());

        assertThat(new SessionService(clock).find(session.accessToken())).isEmpty();
        when(clock.instant()).thenReturn(session.expiresAt().minusSeconds(1));
        assertThat(service.find(session.accessToken())).isPresent();
        when(clock.instant()).thenReturn(session.expiresAt());
        assertThat(service.find(session.accessToken())).isEmpty();
    }
}

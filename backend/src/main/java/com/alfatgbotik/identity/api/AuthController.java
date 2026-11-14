package com.alfatgbotik.identity.api;

import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.identity.service.TelegramAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TelegramAuthService auth;
    private final SessionService sessions;

    public AuthController(TelegramAuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @PostMapping("/telegram")
    public AuthSessionDto login(@RequestBody JsonNode body) {
        return auth.login(TelegramLoginRequest.from(body).initData());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedSession session) {
        sessions.revoke(session);
        return ResponseEntity.noContent().build();
    }
}

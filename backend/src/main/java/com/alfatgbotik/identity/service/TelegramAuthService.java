package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.api.AuthSessionDto;
import com.alfatgbotik.identity.model.IssuedSession;
import com.alfatgbotik.identity.model.TelegramIdentity;
import com.alfatgbotik.identity.api.UserDto;
import com.alfatgbotik.identity.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class TelegramAuthService {

    private final TelegramInitDataValidator validator;
    private final UserService users;
    private final SessionService sessions;

    public TelegramAuthService(TelegramInitDataValidator validator, UserService users, SessionService sessions) {
        this.validator = validator;
        this.users = users;
        this.sessions = sessions;
    }

    public AuthSessionDto login(String initData) {
        TelegramIdentity identity = validator.validate(initData);
        UserDto user = users.getOrCreate(identity.telegramUserId(), identity.displayName());
        IssuedSession session = sessions.issue(user.id());
        return new AuthSessionDto(session.accessToken(), "Bearer", session.expiresAt(), user);
    }
}

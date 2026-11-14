package com.alfatgbotik.identity.api;

import com.alfatgbotik.identity.api.UserDto;

import java.time.Instant;

public record AuthSessionDto(String accessToken, String tokenType, Instant expiresAt, UserDto user) {
}

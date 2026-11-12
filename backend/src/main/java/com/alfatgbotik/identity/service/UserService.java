package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.api.UserDto;
import com.alfatgbotik.identity.model.AppUser;
import com.alfatgbotik.identity.model.BusinessProfile;
import com.alfatgbotik.identity.persistence.AppUserRepository;
import com.alfatgbotik.identity.persistence.BusinessProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository users;
    private final BusinessProfileRepository profiles;

    public UserService(AppUserRepository users, BusinessProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    public UserDto getById(UUID userId) {
        return toDto(users.findById(userId).orElseThrow(UserNotFoundException::new));
    }

    @Transactional
    public UserDto getOrCreate(long telegramUserId, String displayName) {
        return toDto(findOrCreate(telegramUserId, displayName));
    }

    @Transactional
    public UserDto confirmTelegramChat(long telegramUserId, long chatId, String displayName) {
        if (chatId == 0) {
            throw new IllegalArgumentException("chatId must not be zero");
        }
        AppUser user = findOrCreate(telegramUserId, displayName);
        user.confirmTelegramChat(chatId);
        return toDto(user);
    }

    private AppUser findOrCreate(long telegramUserId, String displayName) {
        if (telegramUserId <= 0) {
            throw new IllegalArgumentException("telegramUserId must be positive");
        }
        if (displayName != null && (displayName.isBlank()
                || displayName.codePointCount(0, displayName.length()) > 160)) {
            throw new IllegalArgumentException("displayName must contain 1 to 160 characters");
        }

        int insertedRows = users.insertIfAbsent(UUID.randomUUID(), telegramUserId, displayName);
        AppUser user = users.findByTelegramUserId(telegramUserId).orElseThrow(UserNotFoundException::new);

        if (insertedRows == 1) {
            profiles.saveAndFlush(new BusinessProfile(user.getId()));
        } else if (displayName != null) {
            user.updateDisplayName(displayName);
        }
        return user;
    }

    private UserDto toDto(AppUser user) {
        BusinessProfile profile = profiles.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User has no business profile"));
        return UserDto.from(user, profile);
    }
}

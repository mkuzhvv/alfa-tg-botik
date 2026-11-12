package com.alfatgbotik.identity.api;

import com.alfatgbotik.identity.model.AppUser;
import com.alfatgbotik.identity.model.BusinessProfile;

import java.util.UUID;

public record UserDto(
        UUID id,
        String displayName,
        boolean canReceiveReminders,
        long profileVersion,
        ProfileDto profile
) {

    public static UserDto from(AppUser user, BusinessProfile profile) {
        return new UserDto(
                user.getId(),
                user.getDisplayName(),
                user.canReceiveReminders(),
                profile.getVersion(),
                ProfileDto.from(profile)
        );
    }
}

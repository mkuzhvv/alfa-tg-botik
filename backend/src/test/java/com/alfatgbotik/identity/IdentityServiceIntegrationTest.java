package com.alfatgbotik.identity;

import com.alfatgbotik.identity.api.ProfileDto;
import com.alfatgbotik.identity.api.UserDto;
import com.alfatgbotik.identity.model.BusinessStage;
import com.alfatgbotik.identity.model.Experience;
import com.alfatgbotik.identity.model.TaxRegime;
import com.alfatgbotik.identity.persistence.AppUserRepository;
import com.alfatgbotik.identity.persistence.BusinessProfileRepository;
import com.alfatgbotik.identity.service.BusinessProfileService;
import com.alfatgbotik.identity.service.ProfileUpdateCommand;
import com.alfatgbotik.identity.service.ProfileVersionConflictException;
import com.alfatgbotik.identity.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "telegram.enabled=false")
class IdentityServiceIntegrationTest {

    @Autowired
    private UserService users;

    @Autowired
    private BusinessProfileService profiles;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private BusinessProfileRepository profileRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void deleteTestUsers() {
        userRepository.deleteAllById(createdUserIds);
    }

    @Test
    void createsOnlyOneUserForRepeatedTelegramId() {
        long telegramId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        UserDto first = createUser(telegramId, "Test User");
        UserDto repeated = users.getOrCreate(telegramId, "Updated Name");

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.displayName()).isEqualTo("Updated Name");
        assertThat(repeated.canReceiveReminders()).isFalse();
        assertThat(repeated.profileVersion()).isZero();
        assertThat(repeated.profile().industry()).isNull();
        assertThat(userRepository.countByTelegramUserId(telegramId)).isEqualTo(1);
        assertThat(profileRepository.existsById(first.id())).isTrue();

        UserDto confirmed = users.confirmTelegramChat(telegramId, telegramId, null);
        assertThat(confirmed.canReceiveReminders()).isTrue();
        assertThat(users.getById(first.id()).canReceiveReminders()).isTrue();
        assertThat(userRepository.findById(first.id()).orElseThrow().getTelegramChatId())
                .isEqualTo(telegramId);
    }

    @Test
    void persistsProfileChangesAndSupportsClearing() {
        UserDto user = createUser(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), null);
        ProfileDto updated = profiles.updateProfile(user.id(), new ProfileUpdateCommand(user.profileVersion())
                .businessStage(BusinessStage.LAUNCH)
                .industry("Coffee shop")
                .experience(Experience.BEGINNER)
                .currentTask("Increase sales")
                .taxRegime(TaxRegime.USN_6)
                .timeZone("Europe/Moscow"));

        UserDto loaded = users.getById(user.id());

        assertThat(loaded.profileVersion()).isEqualTo(user.profileVersion() + 1);
        assertThat(loaded.profile()).isEqualTo(updated);
        assertThat(loaded.profile().businessStage()).isEqualTo(BusinessStage.LAUNCH);
        assertThat(loaded.profile().industry()).isEqualTo("Coffee shop");
        assertThat(loaded.profile().experience()).isEqualTo(Experience.BEGINNER);
        assertThat(loaded.profile().taxRegime()).isEqualTo(TaxRegime.USN_6);
        assertThat(loaded.profile().timeZone()).isEqualTo("Europe/Moscow");

        profiles.updateProfile(user.id(), new ProfileUpdateCommand(loaded.profileVersion()).industry(null));
        UserDto cleared = users.getById(user.id());

        assertThat(cleared.profile().industry()).isNull();
        assertThat(cleared.profile().currentTask()).isEqualTo("Increase sales");
        assertThat(cleared.profileVersion()).isEqualTo(loaded.profileVersion() + 1);
    }

    @Test
    void rejectsStaleProfileVersion() {
        UserDto user = createUser(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), null);
        profiles.updateProfile(user.id(), new ProfileUpdateCommand(user.profileVersion()).industry("Retail"));

        assertThatThrownBy(() -> profiles.updateProfile(user.id(),
                new ProfileUpdateCommand(user.profileVersion()).currentTask("Outdated change")))
                .isInstanceOf(ProfileVersionConflictException.class);

        UserDto loaded = users.getById(user.id());
        assertThat(loaded.profile().industry()).isEqualTo("Retail");
        assertThat(loaded.profile().currentTask()).isNull();
        assertThat(loaded.profileVersion()).isEqualTo(user.profileVersion() + 1);
    }

    private UserDto createUser(long telegramId, String displayName) {
        UserDto user = users.getOrCreate(telegramId, displayName);
        createdUserIds.add(user.id());
        return user;
    }
}

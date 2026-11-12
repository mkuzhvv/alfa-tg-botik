package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.api.ProfileDto;
import com.alfatgbotik.identity.model.BusinessProfile;
import com.alfatgbotik.identity.persistence.BusinessProfileRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.UUID;

@Service
public class BusinessProfileService {

    private final BusinessProfileRepository profiles;
    private final TransactionTemplate transactions;

    public BusinessProfileService(BusinessProfileRepository profiles,
                                  PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProfileDto updateProfile(UUID userId, ProfileUpdateCommand command) {
        if (command == null || !command.hasChanges()) {
            throw new IllegalArgumentException("At least one profile field must be provided");
        }
        validateText(command.industry().value(), 100, "industry");
        validateText(command.currentTask().value(), 500, "currentTask");
        validateText(command.timeZone().value(), 64, "timeZone");
        if (command.timeZone().value() != null
                && !ZoneId.getAvailableZoneIds().contains(command.timeZone().value())) {
            throw new IllegalArgumentException("timeZone must be a recognized IANA zone");
        }

        try {
            return transactions.execute(status -> applyChanges(userId, command));
        } catch (OptimisticLockingFailureException exception) {
            throw new ProfileVersionConflictException(exception);
        }
    }

    private ProfileDto applyChanges(UUID userId, ProfileUpdateCommand command) {
        BusinessProfile profile = profiles.findById(userId).orElseThrow(UserNotFoundException::new);
        if (profile.getVersion() != command.expectedVersion()) {
            throw new ProfileVersionConflictException();
        }

        if (command.businessStage().provided()) {
            profile.setBusinessStage(command.businessStage().value());
        }
        if (command.industry().provided()) {
            profile.setIndustry(command.industry().value());
        }
        if (command.experience().provided()) {
            profile.setExperience(command.experience().value());
        }
        if (command.currentTask().provided()) {
            profile.setCurrentTask(command.currentTask().value());
        }
        if (command.taxRegime().provided()) {
            profile.setTaxRegime(command.taxRegime().value());
        }
        if (command.timeZone().provided()) {
            profile.setTimeZone(command.timeZone().value());
        }

        return ProfileDto.from(profile);
    }

    private void validateText(String value, int maxLength, String field) {
        if (value != null && (value.isBlank() || value.codePointCount(0, value.length()) > maxLength)) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
    }
}

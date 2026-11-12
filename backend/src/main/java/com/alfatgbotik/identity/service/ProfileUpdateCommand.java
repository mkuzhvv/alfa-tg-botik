package com.alfatgbotik.identity.service;

import com.alfatgbotik.identity.model.BusinessStage;
import com.alfatgbotik.identity.model.Experience;
import com.alfatgbotik.identity.model.TaxRegime;

public final class ProfileUpdateCommand {

    private final long expectedVersion;
    private Change<BusinessStage> businessStage = Change.unchanged();
    private Change<String> industry = Change.unchanged();
    private Change<Experience> experience = Change.unchanged();
    private Change<String> currentTask = Change.unchanged();
    private Change<TaxRegime> taxRegime = Change.unchanged();
    private Change<String> timeZone = Change.unchanged();

    public ProfileUpdateCommand(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        this.expectedVersion = expectedVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public Change<BusinessStage> businessStage() {
        return businessStage;
    }

    public ProfileUpdateCommand businessStage(BusinessStage value) {
        businessStage = Change.set(value);
        return this;
    }

    public Change<String> industry() {
        return industry;
    }

    public ProfileUpdateCommand industry(String value) {
        industry = Change.set(value);
        return this;
    }

    public Change<Experience> experience() {
        return experience;
    }

    public ProfileUpdateCommand experience(Experience value) {
        experience = Change.set(value);
        return this;
    }

    public Change<String> currentTask() {
        return currentTask;
    }

    public ProfileUpdateCommand currentTask(String value) {
        currentTask = Change.set(value);
        return this;
    }

    public Change<TaxRegime> taxRegime() {
        return taxRegime;
    }

    public ProfileUpdateCommand taxRegime(TaxRegime value) {
        taxRegime = Change.set(value);
        return this;
    }

    public Change<String> timeZone() {
        return timeZone;
    }

    public ProfileUpdateCommand timeZone(String value) {
        timeZone = Change.set(value);
        return this;
    }

    public boolean hasChanges() {
        return businessStage.provided() || industry.provided() || experience.provided()
                || currentTask.provided() || taxRegime.provided() || timeZone.provided();
    }

    public record Change<T>(boolean provided, T value) {

        private static <T> Change<T> unchanged() {
            return new Change<>(false, null);
        }

        private static <T> Change<T> set(T value) {
            return new Change<>(true, value);
        }
    }
}

package com.alfatgbotik.identity.api;

import com.alfatgbotik.identity.model.BusinessProfile;
import com.alfatgbotik.identity.model.BusinessStage;
import com.alfatgbotik.identity.model.Experience;
import com.alfatgbotik.identity.model.TaxRegime;

public record ProfileDto(
        BusinessStage businessStage,
        String industry,
        Experience experience,
        String currentTask,
        TaxRegime taxRegime,
        String timeZone
) {

    public static ProfileDto from(BusinessProfile profile) {
        return new ProfileDto(
                profile.getBusinessStage(),
                profile.getIndustry(),
                profile.getExperience(),
                profile.getCurrentTask(),
                profile.getTaxRegime(),
                profile.getTimeZone()
        );
    }
}

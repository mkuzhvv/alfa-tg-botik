package com.alfatgbotik.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

@Entity
@Table(name = "business_profiles", schema = "app")
public class BusinessProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_stage", length = 16)
    private BusinessStage businessStage;

    @Column(length = 100)
    private String industry;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Experience experience;

    @Column(name = "current_task", length = 500)
    private String currentTask;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime", length = 16)
    private TaxRegime taxRegime;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Version
    @Column(nullable = false)
    private Long version;

    protected BusinessProfile() {
    }

    public BusinessProfile(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BusinessStage getBusinessStage() {
        return businessStage;
    }

    public void setBusinessStage(BusinessStage businessStage) {
        this.businessStage = businessStage;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public Experience getExperience() {
        return experience;
    }

    public void setExperience(Experience experience) {
        this.experience = experience;
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask;
    }

    public TaxRegime getTaxRegime() {
        return taxRegime;
    }

    public void setTaxRegime(TaxRegime taxRegime) {
        this.taxRegime = taxRegime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Long getVersion() {
        return version;
    }
}

package com.alfatgbotik.identity.persistence;

import com.alfatgbotik.identity.model.BusinessProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessProfileRepository extends JpaRepository<BusinessProfile, UUID> {
}

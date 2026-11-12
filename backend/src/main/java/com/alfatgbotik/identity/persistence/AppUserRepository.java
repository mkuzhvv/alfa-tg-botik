package com.alfatgbotik.identity.persistence;

import com.alfatgbotik.identity.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByTelegramUserId(long telegramUserId);

    long countByTelegramUserId(long telegramUserId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO app.users (id, telegram_user_id, display_name)
            VALUES (:id, :telegramUserId, :displayName)
            ON CONFLICT (telegram_user_id) DO NOTHING
            """, nativeQuery = true)

    int insertIfAbsent(@Param("id") UUID id,
                       @Param("telegramUserId") long telegramUserId,
                       @Param("displayName") String displayName);
}

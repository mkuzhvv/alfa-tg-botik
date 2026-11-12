package com.alfatgbotik.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.util.UUID;

@Entity
@Table(name = "users", schema = "app")
@DynamicUpdate
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "telegram_user_id", nullable = false, unique = true)
    private long telegramUserId;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "can_receive_reminders", nullable = false)
    private boolean canReceiveReminders;

    protected AppUser() {
    }

    public UUID getId() {
        return id;
    }

    public long getTelegramUserId() {
        return telegramUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public boolean canReceiveReminders() {
        return canReceiveReminders;
    }

    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void confirmTelegramChat(long telegramChatId) {
        this.telegramChatId = telegramChatId;
        this.canReceiveReminders = true;
    }
}

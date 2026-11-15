package com.alfatgbotik.identity.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TelegramContactRepository {

    private final JdbcTemplate jdbc;

    public TelegramContactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Contact> lock(UUID userId) {
        return jdbc.query("""
                SELECT telegram_chat_id, can_receive_reminders FROM app.users WHERE id = ? FOR UPDATE
                """, (row, number) -> new Contact(row.getObject("telegram_chat_id", Long.class),
                row.getBoolean("can_receive_reminders")), userId).stream().findFirst();
    }

    public void disable(UUID userId) {
        jdbc.update("UPDATE app.users SET can_receive_reminders = false WHERE id = ?", userId);
    }

    public record Contact(Long chatId, boolean canReceiveReminders) {
        public boolean available() {
            return chatId != null && canReceiveReminders;
        }
    }
}

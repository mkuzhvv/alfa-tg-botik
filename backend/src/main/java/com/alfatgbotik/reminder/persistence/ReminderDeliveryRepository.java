package com.alfatgbotik.reminder.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReminderDeliveryRepository {

    private final JdbcTemplate jdbc;

    public ReminderDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Delivery> pending(UUID reminderId) {
        return jdbc.query("""
                SELECT * FROM app.reminder_deliveries WHERE reminder_id = ? AND status IN ('PENDING', 'SENDING')
                """, this::map, reminderId).stream().findFirst();
    }

    public Delivery get(UUID id) {
        return jdbc.query("SELECT * FROM app.reminder_deliveries WHERE id = ?", this::map, id).getFirst();
    }

    public UUID insert(UUID reminderId, long version, Instant dueAt, Instant scheduled, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.reminder_deliveries
                    (id, reminder_id, reminder_version, due_at, scheduled_at, status, retry_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, reminderId, version, Timestamp.from(dueAt), Timestamp.from(scheduled), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    public void sending(UUID id, Instant now) {
        jdbc.update("""
                UPDATE app.reminder_deliveries SET status = 'SENDING', attempts = attempts + 1,
                    retry_at = NULL, updated_at = ? WHERE id = ?
                """, Timestamp.from(now), id);
    }

    public void finish(UUID id, String status, String error, Instant retry, Instant now) {
        jdbc.update("""
                UPDATE app.reminder_deliveries SET status = ?, error_code = ?, retry_at = ?, updated_at = ? WHERE id = ?
                """, status, error, retry == null ? null : Timestamp.from(retry), Timestamp.from(now), id);
    }

    public List<InterruptedDelivery> interrupted() {
        return jdbc.query("""
                SELECT d.id, r.user_id, r.id AS reminder_id FROM app.reminder_deliveries d
                JOIN app.reminders r ON r.id = d.reminder_id WHERE d.status = 'SENDING'
                """, (row, number) -> new InterruptedDelivery(row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("reminder_id", UUID.class)));
    }

    private Delivery map(ResultSet row, int number) throws SQLException {
        Timestamp retry = row.getTimestamp("retry_at");
        return new Delivery(row.getObject("id", UUID.class), row.getObject("reminder_id", UUID.class),
                row.getLong("reminder_version"), row.getTimestamp("due_at").toInstant(), row.getTimestamp("scheduled_at").toInstant(),
                row.getString("status"), row.getInt("attempts"), retry == null ? null : retry.toInstant());
    }

    public record Delivery(UUID id, UUID reminderId, long version, Instant dueAt, Instant scheduledAt,
                           String status, int attempts, Instant retryAt) {
    }

    public record InterruptedDelivery(UUID id, UUID userId, UUID reminderId) {
    }
}

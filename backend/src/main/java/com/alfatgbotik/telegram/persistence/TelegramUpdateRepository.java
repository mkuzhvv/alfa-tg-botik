package com.alfatgbotik.telegram.persistence;

import com.alfatgbotik.telegram.model.StoredTelegramUpdate;
import com.alfatgbotik.telegram.model.TelegramReply;
import com.alfatgbotik.telegram.model.TelegramUpdateStatus;
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
public class TelegramUpdateRepository {

    private final JdbcTemplate jdbc;

    public TelegramUpdateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(UUID id, long botId, int updateId, Instant now) {
        return jdbc.update("""
                INSERT INTO app.telegram_updates
                    (id, bot_id, update_id, status, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?)
                ON CONFLICT (bot_id, update_id) DO NOTHING
                """, id, botId, updateId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public StoredTelegramUpdate find(long botId, int updateId) {
        return jdbc.queryForObject("SELECT * FROM app.telegram_updates WHERE bot_id = ? AND update_id = ?",
                this::map, botId, updateId);
    }

    public void saveReply(UUID id, TelegramReply reply, Instant now) {
        jdbc.update("""
                UPDATE app.telegram_updates SET status = ?, chat_id = ?, response_text = ?, help_button = ?,
                    callback_query_id = ?, callback_text = ?, processed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, reply.isEmpty() ? "IGNORED" : "PENDING", reply.chatId(), reply.text(), reply.helpButton(),
                reply.callbackQueryId(), reply.callbackText(), Timestamp.from(now), Timestamp.from(now), id);
    }

    public Optional<StoredTelegramUpdate> claim(UUID id, Instant now) {
        return jdbc.query("""
                UPDATE app.telegram_updates SET status = 'SENDING', attempts = attempts + 1, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= ?
                RETURNING *
                """, this::map, Timestamp.from(now), id, Timestamp.from(now)).stream().findFirst();
    }

    public void finish(UUID id, TelegramUpdateStatus status, String errorCode, Instant nextAttempt, Instant now) {
        jdbc.update("""
                UPDATE app.telegram_updates SET status = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND status = 'SENDING'
                """, status.name(), errorCode, Timestamp.from(nextAttempt), Timestamp.from(now), id);
    }

    public List<StoredTelegramUpdate> findDue(long botId, Instant now) {
        return jdbc.query("""
                SELECT * FROM app.telegram_updates WHERE bot_id = ? AND status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, update_id LIMIT 5
                """, this::map, botId, Timestamp.from(now));
    }

    public int recoverInterrupted(long botId, Instant before, Instant now) {
        return jdbc.update("""
                UPDATE app.telegram_updates SET status = 'DELIVERY_UNKNOWN', last_error_code = 'INTERRUPTED', updated_at = ?
                WHERE bot_id = ? AND status = 'SENDING' AND updated_at <= ?
                """, Timestamp.from(now), botId, Timestamp.from(before));
    }

    private StoredTelegramUpdate map(ResultSet row, int rowNumber) throws SQLException {
        TelegramReply reply = new TelegramReply(row.getObject("chat_id", Long.class), row.getString("response_text"),
                row.getBoolean("help_button"), row.getString("callback_query_id"), row.getString("callback_text"));
        return new StoredTelegramUpdate(row.getObject("id", UUID.class), row.getLong("bot_id"), row.getInt("update_id"),
                reply, TelegramUpdateStatus.valueOf(row.getString("status")), row.getInt("attempts"),
                row.getTimestamp("next_attempt_at").toInstant());
    }
}

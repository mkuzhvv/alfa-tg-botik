package com.alfatgbotik.reminder.persistence;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.model.MaterialKind;
import com.alfatgbotik.reminder.api.ReminderDto;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import com.alfatgbotik.reminder.model.ReminderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReminderRepository {

    private static final String SELECT = """
            SELECT r.*, m.title AS material_title, m.source_type, m.description, m.is_paid, m.duration, m.url
            FROM app.reminders r
            LEFT JOIN knowledge.materials m ON m.dataset_version = r.dataset_version AND m.id = r.material_id
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ReminderRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<ReminderDto> find(UUID userId, UUID id) {
        return jdbc.query(SELECT + " WHERE r.user_id = ? AND r.id = ?", this::map, userId, id).stream().findFirst();
    }

    public List<ReminderDto> list(UUID userId, ReminderStatus status, int offset, int limit) {
        return jdbc.query(SELECT + """
                 WHERE r.user_id = ? AND (?::varchar IS NULL OR r.status = ?)
                 ORDER BY r.created_at DESC, r.id DESC OFFSET ? LIMIT ?
                """, this::map, userId, name(status), name(status), offset, limit);
    }

    public long count(UUID userId, ReminderStatus status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM app.reminders WHERE user_id = ? AND (?::varchar IS NULL OR status = ?)
                """, Long.class, userId, name(status), name(status));
    }

    public void insert(UUID id, UUID userId, String title, ReminderSchedule schedule, Instant next,
                       UUID materialId, String datasetVersion, UUID stepId, Instant now) {
        jdbc.update("""
                INSERT INTO app.reminders (id, user_id, title, schedule, status, next_fire_at,
                                           material_id, dataset_version, plan_step_id, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, id, userId, title, json.writeValueAsString(schedule), timestamp(next),
                materialId, datasetVersion, stepId, timestamp(now), timestamp(now));
    }

    public void update(ReminderDto old, String title, ReminderSchedule schedule, ReminderStatus status,
                       Instant next, Instant lastSent, String reason, Instant now) {
        jdbc.update("""
                UPDATE app.reminders SET title = ?, schedule = ?::jsonb, status = ?, next_fire_at = ?,
                    last_sent_at = ?, completion_reason = ?, version = version + 1,
                    updated_at = GREATEST(updated_at, ?) WHERE id = ?
                """, title, json.writeValueAsString(schedule), status.name(), timestamp(next), timestamp(lastSent),
                reason, timestamp(now), old.id());
        jdbc.update("""
                UPDATE app.reminder_deliveries SET status = 'SKIPPED', retry_at = NULL, updated_at = ?
                WHERE reminder_id = ? AND status = 'PENDING'
                """, timestamp(now), old.id());
    }

    public void pauseAll(UUID userId, Instant now) {
        jdbc.update("""
                UPDATE app.reminders SET status = 'PAUSED', next_fire_at = NULL, version = version + 1,
                    updated_at = GREATEST(updated_at, ?) WHERE user_id = ? AND status = 'ACTIVE'
                """, timestamp(now), userId);
        jdbc.update("""
                UPDATE app.reminder_deliveries SET status = 'SKIPPED', retry_at = NULL, updated_at = ?
                WHERE status = 'PENDING' AND reminder_id IN (SELECT id FROM app.reminders WHERE user_id = ?)
                """, timestamp(now), userId);
    }

    public boolean tryLockCommand(UUID userId, UUID key) {
        String scope = "reminder.create:" + userId + ":" + key;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, scope));
    }

    public Optional<StoredCommand> findCommand(UUID userId, UUID key) {
        return jdbc.query("""
                SELECT request_body, response_body FROM app.reminder_create_requests WHERE user_id = ? AND request_key = ?
                """, (row, number) -> new StoredCommand(json.readTree(row.getString("request_body")),
                json.readValue(row.getString("response_body"), ReminderDto.class)), userId, key).stream().findFirst();
    }

    public void saveCommand(UUID userId, UUID key, JsonNode request, ReminderDto result, Instant now) {
        jdbc.update("""
                INSERT INTO app.reminder_create_requests (user_id, request_key, request_body, response_body, created_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?)
                """, userId, key, json.writeValueAsString(request), json.writeValueAsString(result), timestamp(now));
    }

    public List<DueReminder> due(Instant now) {
        return jdbc.query("""
                SELECT r.user_id, r.id FROM app.reminders r
                LEFT JOIN app.reminder_deliveries d ON d.reminder_id = r.id AND d.status IN ('PENDING', 'SENDING')
                WHERE r.status = 'ACTIVE' AND r.next_fire_at <= ?
                  AND (d.id IS NULL OR (d.status = 'PENDING' AND d.retry_at <= ?))
                ORDER BY r.next_fire_at, r.id LIMIT 20
                """, (row, number) -> new DueReminder(row.getObject("user_id", UUID.class), row.getObject("id", UUID.class)),
                timestamp(now), timestamp(now));
    }

    private ReminderDto map(ResultSet row, int number) throws SQLException {
        UUID materialId = row.getObject("material_id", UUID.class);
        MaterialCardDto material = materialId == null ? null : new MaterialCardDto(materialId,
                row.getString("material_title"), MaterialKind.valueOf(row.getString("source_type")),
                row.getString("description"), null, row.getBoolean("is_paid"), row.getString("duration"), row.getString("url"));
        return new ReminderDto(row.getObject("id", UUID.class), row.getString("title"),
                ReminderStatus.valueOf(row.getString("status")), json.readValue(row.getString("schedule"), ReminderSchedule.class),
                instant(row, "next_fire_at"), instant(row, "last_sent_at"), row.getString("completion_reason"),
                material, row.getObject("plan_step_id", UUID.class), row.getLong("version"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private String name(ReminderStatus status) {
        return status == null ? null : status.name();
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record StoredCommand(JsonNode request, ReminderDto response) {
    }

    public record DueReminder(UUID userId, UUID reminderId) {
    }
}

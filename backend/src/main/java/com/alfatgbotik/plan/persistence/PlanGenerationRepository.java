package com.alfatgbotik.plan.persistence;

import com.alfatgbotik.plan.api.PlanDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanGenerationRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public PlanGenerationRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean tryLock(UUID userId, UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, scope(userId, key)));
    }

    public void lock(UUID userId, UUID key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope(userId, key));
    }

    private String scope(UUID userId, UUID key) { return "plan.generate:" + userId + ":" + key; }

    public Optional<Request> find(UUID userId, UUID key) {
        return jdbc.query("SELECT * FROM app.plan_generation_requests WHERE user_id = ? AND request_key = ?", (row, number) ->
                new Request(json.readTree(row.getString("request_body")), row.getString("status"), row.getObject("run_id", UUID.class),
                        row.getTimestamp("lease_until").toInstant(), row.getString("response") == null ? null
                        : json.readValue(row.getString("response"), PlanDto.class)), userId, key).stream().findFirst();
    }

    public void start(UUID userId, UUID key, JsonNode body, UUID runId, Instant now) {
        jdbc.update("""
                INSERT INTO app.plan_generation_requests (user_id, request_key, request_body, status, run_id, lease_until, updated_at)
                VALUES (?, ?, ?::jsonb, 'PROCESSING', ?, ?, ?)
                ON CONFLICT (user_id, request_key) DO UPDATE SET status='PROCESSING', run_id=EXCLUDED.run_id,
                    lease_until=EXCLUDED.lease_until, response=NULL, updated_at=EXCLUDED.updated_at
                """, userId, key, json.writeValueAsString(body), runId, Timestamp.from(now.plusSeconds(90)), Timestamp.from(now));
    }

    public void succeed(UUID userId, UUID key, UUID runId, PlanDto result, Instant now) {
        jdbc.update("""
                UPDATE app.plan_generation_requests SET status='SUCCEEDED', response=?::jsonb, updated_at=?
                WHERE user_id=? AND request_key=? AND run_id=? AND status='PROCESSING'
                """, json.writeValueAsString(result), Timestamp.from(now), userId, key, runId);
    }

    public void fail(UUID userId, UUID key, UUID runId, Instant now) {
        jdbc.update("""
                UPDATE app.plan_generation_requests SET status='FAILED', response=NULL, updated_at=?
                WHERE user_id=? AND request_key=? AND run_id=? AND status='PROCESSING'
                """, Timestamp.from(now), userId, key, runId);
    }

    public record Request(JsonNode body, String status, UUID runId, Instant leaseUntil, PlanDto result) { }
}

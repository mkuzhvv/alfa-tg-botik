package com.alfatgbotik.assistant.persistence;

import com.alfatgbotik.assistant.model.AssistantResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActionProposalRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ActionProposalRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void create(UUID userId, UUID requestId, AssistantResponse response, Instant now) {
        String kind = !response.planProposal().isNull() ? "PLAN" : !response.reminderProposal().isNull() ? "REMINDER" : null;
        if (kind == null) return;
        jdbc.update("""
                INSERT INTO app.action_proposals (id, user_id, kind, body, created_at, expires_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?) ON CONFLICT (id) DO NOTHING
                """, requestId, userId, kind, json.writeValueAsString(response), Timestamp.from(now), Timestamp.from(now.plus(24, ChronoUnit.HOURS)));
    }

    public Optional<Proposal> find(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM app.action_proposals WHERE user_id = ? AND id = ?", (row, number) ->
                new Proposal(row.getObject("id", UUID.class), row.getString("kind"), row.getString("status"),
                        json.readValue(row.getString("body"), AssistantResponse.class), row.getTimestamp("expires_at").toInstant(),
                        row.getObject("replacement_id", UUID.class), row.getObject("replacement_version", Long.class),
                        row.getString("result_text")), userId, id).stream().findFirst();
    }

    public void awaitReplacement(UUID id, UUID planId, long version) {
        jdbc.update("""
                UPDATE app.action_proposals SET status = 'AWAITING_REPLACEMENT', replacement_id = ?, replacement_version = ?
                WHERE id = ?
                """, planId, version, id);
    }

    public void confirm(UUID id, Object result, String text) {
        jdbc.update("""
                UPDATE app.action_proposals SET status = 'CONFIRMED', result = ?::jsonb, result_text = ? WHERE id = ?
                """, json.writeValueAsString(result), text, id);
    }

    public void cancel(UUID id) {
        jdbc.update("UPDATE app.action_proposals SET status = 'CANCELLED' WHERE id = ?", id);
    }

    public record Proposal(UUID id, String kind, String status, AssistantResponse response, Instant expiresAt,
                           UUID replacementId, Long replacementVersion, String resultText) { }
}

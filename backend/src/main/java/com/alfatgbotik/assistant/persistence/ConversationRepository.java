package com.alfatgbotik.assistant.persistence;

import com.alfatgbotik.assistant.model.AssistantContext.HistoryMessage;
import com.alfatgbotik.assistant.model.AssistantResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ConversationRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Turn begin(UUID userId, UUID requestId, String message, Instant now) {
        jdbc.update("""
                INSERT INTO app.conversations (id, user_id, created_at) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), userId, Timestamp.from(now));
        UUID conversation = jdbc.queryForObject("SELECT id FROM app.conversations WHERE user_id = ?", UUID.class, userId);
        jdbc.update("""
                INSERT INTO app.conversation_turns (id, conversation_id, user_text, status, created_at)
                VALUES (?, ?, ?, 'PROCESSING', ?) ON CONFLICT (id) DO NOTHING
                """, requestId, conversation, message, Timestamp.from(now));
        return jdbc.query("""
                SELECT id, user_text, status, assistant_text FROM app.conversation_turns WHERE id = ? AND conversation_id = ?
                """, (row, number) -> new Turn(row.getObject("id", UUID.class), row.getString("user_text"),
                row.getString("status"), row.getString("assistant_text")), requestId, conversation).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("Conversation request owner mismatch"));
    }

    public List<HistoryMessage> history(UUID userId) {
        List<List<HistoryMessage>> turns = jdbc.query("""
                SELECT t.user_text, t.assistant_text FROM app.conversation_turns t
                JOIN app.conversations c ON c.id = t.conversation_id
                WHERE c.user_id = ? AND t.status = 'COMPLETED' ORDER BY t.position DESC LIMIT 2
                """, (row, number) -> List.of(new HistoryMessage("USER", row.getString("user_text")),
                new HistoryMessage("ASSISTANT", row.getString("assistant_text"))), userId);
        Collections.reverse(turns);
        List<HistoryMessage> result = new ArrayList<>();
        turns.forEach(result::addAll);
        return List.copyOf(result);
    }

    public void complete(UUID id, AssistantResponse response, String reply, String model, String revision,
                         String promptVersion, String datasetVersion, Instant now) {
        jdbc.update("""
                UPDATE app.conversation_turns SET status = 'COMPLETED', response = ?::jsonb, assistant_text = ?,
                    model_name = ?, model_revision = ?, prompt_version = ?, dataset_version = ?, completed_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, json.writeValueAsString(response), reply, model, revision, promptVersion, datasetVersion, Timestamp.from(now), id);
    }

    public void fail(UUID id, String reply, String code, Instant now) {
        jdbc.update("""
                UPDATE app.conversation_turns SET status = 'FAILED', assistant_text = ?, error_code = ?, completed_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, reply, code, Timestamp.from(now), id);
    }

    public void recoverInterrupted(String reply, Instant now) {
        jdbc.update("""
                UPDATE app.conversation_turns SET status = 'FAILED', assistant_text = ?, error_code = 'INTERRUPTED', completed_at = ?
                WHERE status = 'PROCESSING'
                """, reply, Timestamp.from(now));
    }

    public record Turn(UUID id, String message, String status, String reply) {
    }
}

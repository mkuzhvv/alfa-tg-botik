package com.alfatgbotik.assistant.persistence;

import com.alfatgbotik.assistant.model.AssistantContext.Material;
import com.alfatgbotik.assistant.model.AssistantContext.Source;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AssistantKnowledgeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AssistantKnowledgeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Source> sources(String version, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT c.id, c.material_id, c.text, m.title FROM knowledge.chunks c
                JOIN knowledge.materials m ON m.dataset_version = c.dataset_version AND m.id = c.material_id
                WHERE c.dataset_version = :version AND c.id IN (:ids) AND m.answer_allowed AND NOT m.is_paid
                """, Map.of("version", version, "ids", ids), (row, number) -> new Source(
                row.getObject("id", UUID.class), row.getObject("material_id", UUID.class),
                row.getString("title"), row.getString("text")));
    }

    public List<Material> materials(String version, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, title, source_type, description, is_paid, duration, url
                FROM knowledge.materials WHERE dataset_version = :version AND id IN (:ids)
                """, Map.of("version", version, "ids", ids), (row, number) -> new Material(
                row.getObject("id", UUID.class), row.getString("title"), row.getString("source_type"),
                row.getString("description"), row.getBoolean("is_paid"), row.getString("duration"), row.getString("url")));
    }
}

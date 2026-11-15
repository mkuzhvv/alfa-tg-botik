package com.alfatgbotik.plan.persistence;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.model.MaterialKind;
import com.alfatgbotik.plan.api.PlanDto;
import com.alfatgbotik.plan.api.PlanStepDto;
import com.alfatgbotik.plan.model.Plan;
import com.alfatgbotik.plan.model.PlanStatus;
import com.alfatgbotik.plan.model.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockUser(UUID userId) {
        return !jdbc.query("SELECT id FROM app.users WHERE id = ? FOR UPDATE",
                (row, number) -> row.getObject("id", UUID.class), userId).isEmpty();
    }

    public Optional<Plan> findCurrentHeader(UUID userId) {
        return jdbc.query("SELECT * FROM app.plans WHERE user_id = ? AND status = 'ACTIVE'",
                this::header, userId).stream().findFirst();
    }

    public Optional<Plan> findOwnedHeader(UUID userId, UUID planId) {
        return jdbc.query("SELECT * FROM app.plans WHERE user_id = ? AND id = ?",
                this::header, userId, planId).stream().findFirst();
    }

    public Optional<PlanDto> findCurrent(UUID userId) {
        return load("p.status = 'ACTIVE'", userId);
    }

    public Optional<PlanDto> findOwned(UUID userId, UUID planId) {
        return load("p.id = ?", userId, planId);
    }

    public Optional<StepStatus> findStepStatus(UUID planId, UUID stepId) {
        return jdbc.query("SELECT status FROM app.plan_steps WHERE plan_id = ? AND id = ?",
                (row, number) -> StepStatus.valueOf(row.getString("status")), planId, stepId).stream().findFirst();
    }

    public boolean ownsStep(UUID userId, UUID stepId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.plan_steps s JOIN app.plans p ON p.id = s.plan_id
                               WHERE p.user_id = ? AND s.id = ?)
                """, Boolean.class, userId, stepId));
    }

    public boolean stepContainsMaterial(UUID stepId, UUID materialId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.plan_step_materials WHERE step_id = ? AND material_id = ?)
                """, Boolean.class, stepId, materialId));
    }

    public void insertPlan(UUID planId, UUID userId, String goal, Instant now) {
        jdbc.update("""
                INSERT INTO app.plans (id, user_id, goal, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """, planId, userId, goal, Timestamp.from(now), Timestamp.from(now));
    }

    public void insertStep(UUID stepId, UUID planId, int position, String title, String description) {
        jdbc.update("""
                INSERT INTO app.plan_steps (id, plan_id, position, title, description, status)
                VALUES (?, ?, ?, ?, ?, 'TODO')
                """, stepId, planId, position, title, description);
    }

    public void insertMaterial(UUID stepId, int position, UUID materialId, String datasetVersion, String reason) {
        jdbc.update("""
                INSERT INTO app.plan_step_materials (step_id, position, material_id, dataset_version, recommendation_reason)
                VALUES (?, ?, ?, ?, ?)
                """, stepId, position, materialId, datasetVersion, reason);
    }

    public void updateStep(UUID planId, UUID stepId, StepStatus status) {
        jdbc.update("UPDATE app.plan_steps SET status = ? WHERE plan_id = ? AND id = ?", status.name(), planId, stepId);
    }

    public void bumpVersion(UUID planId, Instant now) {
        jdbc.update("UPDATE app.plans SET version = version + 1, updated_at = ? WHERE id = ?", Timestamp.from(now), planId);
    }

    public void archive(UUID planId, Instant now) {
        jdbc.update("""
                UPDATE app.plans SET status = 'ARCHIVED', version = version + 1, updated_at = ? WHERE id = ?
                """, Timestamp.from(now), planId);
    }

    private Optional<PlanDto> load(String condition, Object... parameters) {
        String sql = """
                SELECT p.id AS plan_id, p.user_id, p.goal, p.status AS plan_status, p.version, p.created_at, p.updated_at,
                    s.id AS step_id, s.position AS step_position, s.title AS step_title,
                    s.description AS step_description, s.status AS step_status,
                    m.id AS material_id, m.title AS material_title, m.source_type, m.description AS material_description,
                    m.is_paid, m.duration, m.url, sm.recommendation_reason
                FROM app.plans p
                JOIN app.plan_steps s ON s.plan_id = p.id
                JOIN app.plan_step_materials sm ON sm.step_id = s.id
                JOIN knowledge.materials m ON m.dataset_version = sm.dataset_version AND m.id = sm.material_id
                WHERE p.user_id = ? AND
                """ + condition + " ORDER BY s.position, sm.position";
        return jdbc.query(sql, result -> {
            if (!result.next()) {
                return Optional.empty();
            }
            UUID planId = result.getObject("plan_id", UUID.class);
            String goal = result.getString("goal");
            PlanStatus status = PlanStatus.valueOf(result.getString("plan_status"));
            long version = result.getLong("version");
            Instant createdAt = result.getTimestamp("created_at").toInstant();
            Instant updatedAt = result.getTimestamp("updated_at").toInstant();
            Map<UUID, StepBuilder> steps = new LinkedHashMap<>();
            do {
                UUID stepId = result.getObject("step_id", UUID.class);
                StepBuilder step = steps.get(stepId);
                if (step == null) {
                    step = new StepBuilder(stepId, result.getInt("step_position"), result.getString("step_title"),
                            result.getString("step_description"), StepStatus.valueOf(result.getString("step_status")));
                    steps.put(stepId, step);
                }
                step.materials.add(new MaterialCardDto(result.getObject("material_id", UUID.class),
                        result.getString("material_title"), MaterialKind.valueOf(result.getString("source_type")),
                        result.getString("material_description"), result.getString("recommendation_reason"),
                        result.getBoolean("is_paid"), result.getString("duration"), result.getString("url")));
            } while (result.next());
            return Optional.of(new PlanDto(planId, goal, status, version,
                    steps.values().stream().map(StepBuilder::toDto).toList(), createdAt, updatedAt));
        }, parameters);
    }

    private Plan header(ResultSet row, int number) throws SQLException {
        return new Plan(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class), row.getString("goal"),
                PlanStatus.valueOf(row.getString("status")), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private static final class StepBuilder {

        private final UUID id;
        private final int position;
        private final String title;
        private final String description;
        private final StepStatus status;
        private final List<MaterialCardDto> materials = new ArrayList<>();

        private StepBuilder(UUID id, int position, String title, String description, StepStatus status) {
            this.id = id;
            this.position = position;
            this.title = title;
            this.description = description;
            this.status = status;
        }

        private PlanStepDto toDto() {
            return new PlanStepDto(id, position, title, description, status, List.copyOf(materials));
        }
    }
}

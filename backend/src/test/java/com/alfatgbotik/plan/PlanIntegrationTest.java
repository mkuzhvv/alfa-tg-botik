package com.alfatgbotik.plan;

import com.alfatgbotik.catalog.service.DatasetReader;
import com.alfatgbotik.common.error.RequestIdFilter;
import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.api.PlanDto;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.model.PlanReplacement;
import com.alfatgbotik.plan.model.PlanStatus;
import com.alfatgbotik.plan.model.StepStatus;
import com.alfatgbotik.plan.persistence.PlanRepository;
import com.alfatgbotik.plan.service.PlanConflictException;
import com.alfatgbotik.plan.service.PlanMaterialsNotFoundException;
import com.alfatgbotik.plan.service.PlanService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "telegram.enabled=false")
class PlanIntegrationTest {

    private static final String DATABASE = "stage6_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PG = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("DB_PORT", "5432") + "/";
    private static final UUID FREE_SOURCE = UUID.randomUUID();
    private static final UUID PAID_SOURCE = UUID.randomUUID();
    private static final UUID MATERIAL_NAMESPACE = UUID.fromString("a1fa0000-0000-4000-8000-000000000001");
    private static final UUID FREE = DatasetReader.uuid5(MATERIAL_NAMESPACE, "COURSE:" + FREE_SOURCE);
    private static final UUID PAID = DatasetReader.uuid5(MATERIAL_NAMESPACE, "COURSE:" + PAID_SOURCE);
    private static final UUID CHUNK = DatasetReader.uuid5(
            UUID.fromString("a1fa0000-0000-4000-8000-000000000002"), FREE + ":1");

    @Autowired
    PlanService plans;
    @Autowired
    UserService users;
    @Autowired
    SessionService sessions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    RequestIdFilter requestIds;
    @Autowired
    WebApplicationContext context;
    @MockitoSpyBean
    PlanRepository repository;

    private UUID owner;
    private UUID other;
    private String token;
    private String otherToken;
    private MockMvc mvc;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        properties.add("spring.datasource.url", () -> PG + DATABASE + "?currentSchema=app,knowledge,public");
    }

    @AfterAll
    static void dropTestDatabase(@Autowired DataSource datasource) throws Exception {
        datasource.unwrap(HikariDataSource.class).close();
        if (!DATABASE.matches("stage6_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test database name");
        }
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void prepareUsersAndCatalog() {
        reset(repository);
        seedCatalog("catalog-" + UUID.randomUUID(), "Free course");
        owner = users.getOrCreate(ThreadLocalRandom.current().nextLong(1L << 49, 1L << 50), "Plan test").id();
        other = users.getOrCreate(ThreadLocalRandom.current().nextLong(1L << 49, 1L << 50), "Other test").id();
        token = sessions.issue(owner).accessToken();
        otherToken = sessions.issue(other).accessToken();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
    }

    @AfterEach
    void revokeSessions() {
        sessions.find(token).ifPresent(sessions::revoke);
        sessions.find(otherToken).ifPresent(sessions::revoke);
    }

    @Test
    void returnsJsonNullForMissingPlanAndPersistsOrderedStepsAndReasons() throws Exception {
        assertThat(mvc.perform(get("/api/v1/plans/current").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("null");
        PlanDto saved = save(owner, null);

        assertThat(saved.version()).isZero();
        assertThat(saved.goal()).isEqualTo("Grow the business");
        assertThat(saved.steps()).extracting(step -> step.position()).containsExactly(1, 2, 3);
        assertThat(saved.steps()).allMatch(step -> step.status() == StepStatus.TODO);
        assertThat(saved.steps().getFirst().materials()).extracting(card -> card.id()).containsExactly(FREE, PAID);
        assertThat(saved.steps().getFirst().materials().getFirst().recommendationReason()).isEqualTo("Useful for this goal");
        assertThat(saved.steps().getFirst().materials().get(1).recommendationReason()).isNull();

        var response = mvc.perform(get("/api/v1/plans/" + saved.id()).header("Authorization", bearer(token)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "goal", "status", "version", "steps",
                "createdAt", "updatedAt");
        assertThat(body.get("steps").get(0).propertyNames())
                .containsExactlyInAnyOrder("id", "position", "title", "description", "status", "materials");
    }

    @Test
    void updatesWholePlanVersionAndArchivesReadOnlyPlan() throws Exception {
        PlanDto plan = save(owner, null);
        UUID step = plan.steps().getFirst().id();
        var changed = mvc.perform(patch("/api/v1/plans/" + plan.id() + "/steps/" + step)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"expectedVersion\":0,\"status\":\"DONE\"}")).andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(plans.getById(owner, plan.id()).version()).isEqualTo(1);
        assertThat(plans.updateStep(owner, plan.id(), step, 1, StepStatus.DONE).version()).isEqualTo(1);
        assertProblem(mvc.perform(patch("/api/v1/plans/" + plan.id() + "/steps/" + step)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"expectedVersion\":0,\"status\":\"TODO\"}")).andReturn(), 409, "VERSION_CONFLICT");
        var archived = mvc.perform(post("/api/v1/plans/" + plan.id() + "/archive")
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"expectedVersion\":1}")).andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        PlanDto loaded = plans.getById(owner, plan.id());
        assertThat(loaded.status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThat(loaded.version()).isEqualTo(2);
        assertThat(loaded.steps().getFirst().status()).isEqualTo(StepStatus.DONE);
        assertThat(plans.getCurrent(owner)).isNull();
        assertProblem(mvc.perform(patch("/api/v1/plans/" + plan.id() + "/steps/" + step)
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"expectedVersion\":2,\"status\":\"TODO\"}")).andReturn(), 409, "PLAN_ARCHIVED");
    }

    @Test
    void rejectsForeignPlansAndStepsAndRequiresAuthentication() throws Exception {
        PlanDto plan = save(owner, null);
        PlanDto otherPlan = save(other, null);
        assertThat(mvc.perform(get("/api/v1/plans/current")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertProblem(mvc.perform(get("/api/v1/plans/" + plan.id()).header("Authorization", bearer(otherToken)))
                .andReturn(), 404, "NOT_FOUND");
        assertProblem(mvc.perform(post("/api/v1/plans/" + plan.id() + "/archive")
                .header("Authorization", bearer(otherToken)).contentType("application/json")
                .content("{\"expectedVersion\":0}")).andReturn(), 404, "NOT_FOUND");
        assertProblem(mvc.perform(patch("/api/v1/plans/" + plan.id() + "/steps/" + otherPlan.steps().getFirst().id())
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"expectedVersion\":0,\"status\":\"DONE\"}")).andReturn(), 404, "NOT_FOUND");
        assertProblem(mvc.perform(get("/api/v1/plans/not-a-uuid").header("Authorization", bearer(token)))
                .andReturn(), 400, "MALFORMED_REQUEST");
    }

    @Test
    void replacesOnlyConfirmedCurrentVersionAndPreservesMaterialSnapshot() {
        PlanDto original = save(owner, null);
        assertThatThrownBy(() -> save(owner, null)).isInstanceOf(PlanConflictException.class)
                .extracting(exception -> ((PlanConflictException) exception).getCode())
                .isEqualTo("PLAN_REPLACEMENT_REQUIRED");
        assertThatThrownBy(() -> save(owner, new PlanReplacement(original.id(), 1)))
                .isInstanceOf(PlanConflictException.class);
        seedCatalog("catalog-" + UUID.randomUUID(), "Updated course title");
        PlanDto replacement = save(owner, new PlanReplacement(original.id(), 0));

        assertThat(plans.getById(owner, original.id()).status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThat(plans.getById(owner, original.id()).steps().getFirst().materials().getFirst().title())
                .isEqualTo("Free course");
        assertThat(replacement.steps().getFirst().materials().getFirst().title()).isEqualTo("Updated course title");
        assertThat(plans.getCurrent(owner).id()).isEqualTo(replacement.id());
    }

    @Test
    void missingMaterialsAndLateDatabaseFailureLeaveOriginalActive() {
        PlanDto original = save(owner, null);
        PlanReplacement confirmation = new PlanReplacement(original.id(), 0);
        PlanProposal missing = new PlanProposal("New goal", List.of(
                new PlanProposal.Step("One", "Description", List.of(UUID.randomUUID())),
                new PlanProposal.Step("Two", "Description", List.of(FREE)),
                new PlanProposal.Step("Three", "Description", List.of(FREE))));
        assertThatThrownBy(() -> plans.saveProposal(owner, missing, Map.of(), confirmation))
                .isInstanceOf(PlanMaterialsNotFoundException.class);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Simulated failure after material insert");
        }).when(repository).insertMaterial(any(UUID.class), anyInt(), any(UUID.class), anyString(), nullable(String.class));

        assertThatThrownBy(() -> save(owner, confirmation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(plans.getCurrent(owner)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.plans WHERE user_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void concurrentReplacementHasOneWinner() throws Exception {
        PlanDto original = save(owner, null);
        PlanReplacement confirmation = new PlanReplacement(original.id(), 0);
        List<String> outcomes = race(confirmation);

        assertThat(outcomes).containsExactlyInAnyOrder("SAVED", "VERSION_CONFLICT");
        assertThat(plans.getById(owner, original.id()).status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.plans WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void concurrentCreationAndDatabaseConstraintAllowOneActivePlan() throws Exception {
        assertThat(race(null)).containsExactlyInAnyOrder("SAVED", "PLAN_REPLACEMENT_REQUIRED");
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app.plans (id, user_id, goal, status, created_at, updated_at)
                VALUES (?, ?, 'Duplicate', 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), owner, Timestamp.from(now), Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"expectedVersion\":0,\"status\":0}|400|MALFORMED_REQUEST",
            "{\"expectedVersion\":\"0\",\"status\":\"DONE\"}|400|MALFORMED_REQUEST",
            "{\"expectedVersion\":0,\"status\":\"DONE\",\"userId\":null}|400|MALFORMED_REQUEST",
            "{\"expectedVersion\":0,\"status\":null}|422|VALIDATION_FAILED",
            "{\"expectedVersion\":0,\"status\":\"PAUSED\"}|422|VALIDATION_FAILED",
            "{\"status\":\"DONE\"}|422|VALIDATION_FAILED"
    })
    void rejectsMalformedStepChanges(String body, int status, String code) throws Exception {
        PlanDto plan = save(owner, null);
        assertProblem(mvc.perform(patch("/api/v1/plans/" + plan.id() + "/steps/" + plan.steps().getFirst().id())
                .header("Authorization", bearer(token)).contentType("application/json").content(body))
                .andReturn(), status, code);
        assertThat(plans.getById(owner, plan.id()).version()).isZero();
    }

    private List<String> race(PlanReplacement confirmation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                try {
                    save(owner, confirmation);
                    return "SAVED";
                } catch (PlanConflictException exception) {
                    return exception.getCode();
                }
            })).toList();
            start.countDown();
            return List.of(tasks.get(0).get(5, TimeUnit.SECONDS), tasks.get(1).get(5, TimeUnit.SECONDS));
        }
    }

    private PlanDto save(UUID userId, PlanReplacement confirmation) {
        return plans.saveProposal(userId, new PlanProposal("  Grow the business  ", List.of(
                new PlanProposal.Step("First", "First description", List.of(FREE, PAID)),
                new PlanProposal.Step("Second", "Second description", List.of(FREE)),
                new PlanProposal.Step("Third", "Third description", List.of(FREE)))),
                Map.of(FREE, "Useful for this goal"), confirmation);
    }

    private void seedCatalog(String version, String title) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO knowledge.datasets (version, created_at, source_snapshot, materials_count, chunks_count,
                    materials_sha256, chunks_sha256, imported_at) VALUES (?, ?, ?, 2, 1, ?, ?, ?)
                """, version, Timestamp.from(now), "sha256:" + "a".repeat(64), "a".repeat(64),
                "b".repeat(64), Timestamp.from(now));
        for (UUID id : List.of(FREE, PAID)) {
            boolean paid = id.equals(PAID);
            jdbc.update("""
                    INSERT INTO knowledge.materials (dataset_version, id, source_type, source_id, title, description,
                        url, is_paid, source_cost, answer_allowed, external_metadata_allowed, duration, topics,
                        industries, parent_material_ids, source_updated_at)
                    VALUES (?, ?, 'COURSE', ?, ?, 'Test course description', 'https://example.test/course', ?, ?, ?,
                        FALSE, NULL, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, NULL)
                    """, version, id, paid ? PAID_SOURCE : FREE_SOURCE, paid ? "Paid course" : title,
                    paid, paid ? 1000L : null, !paid);
        }
        String text = "Educational text for testing a saved plan and its progress.";
        jdbc.update("""
                INSERT INTO knowledge.chunks (dataset_version, id, material_id, position, heading, text, content_hash,
                    external_text_allowed) VALUES (?, ?, ?, 1, NULL, ?, ?, FALSE)
                """, version, CHUNK, FREE, text, DatasetReader.hash(text.getBytes(StandardCharsets.UTF_8)));
        UUID index = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge.search_indexes (id, dataset_version, model_name, model_revision, dimensions,
                    format_version, vector_count, created_at) VALUES (?, ?, 'embeddinggemma:300m', ?, 768,
                    'embeddinggemma-v1', 3, ?)
                """, index, version, "sha256:" + "a".repeat(64), Timestamp.from(now));
        float[] vector = new float[768];
        vector[0] = 1;
        for (UUID id : List.of(FREE, PAID, CHUNK)) {
            jdbc.update("""
                    INSERT INTO knowledge.search_vectors (index_id, dataset_version, document_id, material_id,
                        chunk_id, embedding) VALUES (?, ?, ?, ?, ?, ?::public.vector)
                    """, index, version, id, id.equals(PAID) ? PAID : FREE, id.equals(CHUNK) ? CHUNK : null,
                    Arrays.toString(vector));
        }
        jdbc.update("""
                INSERT INTO knowledge.catalog_state (singleton, active_index_id) VALUES (TRUE, ?)
                ON CONFLICT (singleton) DO UPDATE SET active_index_id = EXCLUDED.active_index_id
                """, index);
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        var body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").stringValue()).isEqualTo(code);
        assertThat(body.get("requestId").stringValue()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
    }

    private String bearer(String value) {
        return "Bearer " + value;
    }
}

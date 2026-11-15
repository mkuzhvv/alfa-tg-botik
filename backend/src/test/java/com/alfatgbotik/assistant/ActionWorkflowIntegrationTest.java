package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.integration.OllamaChatGateway;
import com.alfatgbotik.assistant.persistence.ActionProposalRepository;
import com.alfatgbotik.assistant.retrieval.CatalogSearchResult;
import com.alfatgbotik.assistant.retrieval.CatalogSearchService;
import com.alfatgbotik.assistant.service.ActionException;
import com.alfatgbotik.assistant.service.ActionProposalService;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.assistant.service.ConversationService;
import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.model.SearchHit;
import com.alfatgbotik.common.error.RequestIdFilter;
import com.alfatgbotik.identity.service.BusinessProfileService;
import com.alfatgbotik.identity.service.ProfileUpdateCommand;
import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.model.StepStatus;
import com.alfatgbotik.plan.service.PlanConflictException;
import com.alfatgbotik.plan.service.PlanGenerationService;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.service.ReminderService;
import com.alfatgbotik.telegram.TelegramActions;
import com.alfatgbotik.telegram.TelegramReplySender;
import com.alfatgbotik.telegram.TelegramUpdateHandler;
import com.alfatgbotik.telegram.persistence.TelegramUpdateRepository;
import com.alfatgbotik.telegram.service.TelegramUpdateProcessor;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"telegram.enabled=false", "assistant.timeout=3s"})
class ActionWorkflowIntegrationTest {

    private static final String DATABASE = "stage9_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PG = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("DB_PORT", "5432") + "/";
    private static final UUID MATERIAL = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();
    @Autowired ConversationService conversations;
    @Autowired ActionProposalService actions;
    @Autowired ActionProposalRepository proposals;
    @Autowired PlanService plans;
    @Autowired PlanGenerationService generation;
    @Autowired ReminderService reminders;
    @Autowired BusinessProfileService profiles;
    @Autowired UserService users;
    @Autowired SessionService sessions;
    @Autowired TelegramActions ui;
    @Autowired TelegramUpdateRepository updates;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIds;
    @MockitoBean AssistantModel regularModel;
    @MockitoBean OllamaChatGateway model;
    @MockitoBean CatalogSearchService search;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private UUID owner;
    private UUID other;
    private String token;
    private MockMvc mvc;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        properties.add("spring.datasource.url", () -> PG + DATABASE + "?currentSchema=app,knowledge,public");
    }

    @AfterAll
    static void dropDatabase(@Autowired DataSource datasource, @Autowired org.quartz.Scheduler scheduler) throws Exception {
        scheduler.shutdown(true);
        datasource.unwrap(HikariDataSource.class).close();
        if (!DATABASE.matches("stage9_test_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected test database name");
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", ""); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void prepare() {
        reset(model, regularModel, search, clock);
        now.set(Instant.parse("2026-11-15T10:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.execute("TRUNCATE app.users CASCADE");
        seedCatalog();
        owner = users.confirmTelegramChat(7_200_000_001L, 7_200_000_001L, "Action test").id();
        other = users.getOrCreate(7_200_000_002L, "Other test").id();
        token = sessions.issue(owner).accessToken();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
        when(search.search(anyString(), anyInt(), anyInt(), anyDouble())).thenReturn(new CatalogSearchResult(
                new CatalogIndex(UUID.randomUUID(), "stage9-test", new EmbeddingModelInfo("test", "a".repeat(64), 768), 2),
                List.of(new SearchHit(MATERIAL, CHUNK, 0.9)), List.of(new SearchHit(MATERIAL, null, 0.9))));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            JsonNode input = json.valueToTree(invocation.getArgument(1));
            if (input.has("passages")) return planReply(input);
            return new AssistantModel.ModelReply("""
                    {"changes":[{"field":"industry","value":"кофейня","evidence":"У меня кофейня"},
                    {"field":"experience","value":"BEGINNER","evidence":"Я новичок"}]}
                    """, "test-model", "a".repeat(64));
        }).when(model).complete(anyString(), any(), anyMap(), anyInt(), any());
    }

    @Test
    void appliesOnlyVerifiedProfilePatchOnce() {
        UUID id = UUID.randomUUID();
        String reply = conversations.consult(owner, id, "У меня кофейня. Я новичок.");
        assertThat(reply).contains("Профиль обновлён");
        assertThat(users.getById(owner).profile().industry()).isEqualTo("кофейня");
        assertThat(users.getById(owner).profile().experience().name()).isEqualTo("BEGINNER");
        assertThat(users.getById(owner).profileVersion()).isEqualTo(1);
        assertThat(conversations.consult(owner, id, "У меня кофейня. Я новичок.")).isEqualTo(reply);
        assertThat(users.getById(owner).profileVersion()).isEqualTo(1);
        verify(model, times(1)).complete(anyString(), any(), anyMap(), anyInt(), any());
        verifyNoInteractions(regularModel);
    }

    @Test
    void requiresPlanConfirmationAndReturnsOriginalReceiptOnRepeat() {
        UUID id = proposal("Составь план привлечения клиентов");
        assertThat(plans.getCurrent(owner)).isNull();
        assertThat(ui.proposalButtons(owner, id)).hasSize(2);
        var saved = actions.confirm(owner, id, false);
        assertThat(saved.text()).contains("сохранён");
        var plan = plans.getCurrent(owner);
        assertThat(plan.steps()).hasSize(3);
        assertThat(actions.confirm(owner, id, false)).isEqualTo(saved);
        now.set(now.get().plusSeconds(25 * 3600));
        assertThat(actions.confirm(owner, id, false)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.plans", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> actions.confirm(other, id, false)).isInstanceOfSatisfying(ActionException.class,
                exception -> assertThat(exception.status()).isEqualTo(404));
    }

    @Test
    void separatelyConfirmsReplacementAndRejectsStalePlanVersion() {
        actions.confirm(owner, proposal("Составь план привлечения клиентов"), false);
        var old = plans.getCurrent(owner);
        UUID next = proposal("Составь план увеличения повторных покупок");
        assertThat(actions.confirm(owner, next, false).replacementProposalId()).isEqualTo(next);
        assertThat(plans.getCurrent(owner).id()).isEqualTo(old.id());
        plans.updateStep(owner, old.id(), old.steps().getFirst().id(), old.version(), StepStatus.DONE);
        assertThatThrownBy(() -> actions.confirm(owner, next, true)).isInstanceOf(PlanConflictException.class);
        assertThat(plans.getCurrent(owner).id()).isEqualTo(old.id());
        actions.confirm(owner, next, false);
        actions.confirm(owner, next, true);
        assertThat(plans.getCurrent(owner).id()).isNotEqualTo(old.id());
        assertThat(plans.getById(owner, old.id()).status().name()).isEqualTo("ARCHIVED");
    }

    @Test
    void expiresProposalAndDoesNotChangeExistingPlan() {
        UUID id = proposal("Составь план привлечения клиентов");
        now.set(now.get().plusSeconds(24 * 3600));
        assertThatThrownBy(() -> actions.confirm(owner, id, false)).isInstanceOfSatisfying(ActionException.class,
                exception -> assertThat(exception.code()).isEqualTo("PROPOSAL_EXPIRED"));
        assertThat(plans.getCurrent(owner)).isNull();
    }

    @Test
    void createsReminderOnlyAfterConfirmationAndRechecksFreshTime() {
        UUID id = proposal("Напомни через 1 минуту по Москве продолжить курс");
        assertThat(reminders.list(owner, null, 0, 20).total()).isZero();
        var receipt = actions.confirm(owner, id, false);
        assertThat(actions.confirm(owner, id, false)).isEqualTo(receipt);
        assertThat(reminders.list(owner, ReminderStatus.ACTIVE, 0, 20).total()).isEqualTo(1);
        assertThat(users.getById(owner).profile().timeZone()).isNull();
        UUID stale = proposal("Напомни через 1 минуту по Москве продолжить курс");
        now.set(now.get().plusSeconds(61));
        assertThatThrownBy(() -> actions.confirm(owner, stale, false)).isInstanceOf(com.alfatgbotik.common.error.RequestValidationException.class);
        assertThat(reminders.list(owner, null, 0, 20).total()).isEqualTo(1);
        assertThat(proposals.find(owner, stale).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void cancelsUnconfirmedProposalWithoutCreatingBusinessRecord() {
        UUID id = proposal("Напомни завтра в 9 по Москве продолжить курс");
        actions.cancel(owner, id);
        assertThat(actions.confirm(owner, id, false).text()).contains("отменено");
        assertThat(reminders.list(owner, null, 0, 20).total()).isZero();
    }

    @Test
    void generationUsesGoalFallbackAndReplaysOriginalResponseBeforeStateChecks() throws Exception {
        profiles.updateProfile(owner, new ProfileUpdateCommand(0).currentTask("Привлечь клиентов"));
        UUID key = UUID.randomUUID();
        var first = postGenerate(key, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var firstBody = body(first);
        assertThat(firstBody.get("goal").stringValue()).isEqualTo("Привлечь клиентов");
        var plan = plans.getCurrent(owner);
        plans.archive(owner, plan.id(), plan.version());
        profiles.updateProfile(owner, new ProfileUpdateCommand(1).currentTask("Другая задача"));
        reset(model);
        var repeat = postGenerate(key, null);
        assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(repeat)).isEqualTo(firstBody);
        verifyNoInteractions(model);
        assertProblem(postGenerate(key, "{\"goal\":\"Привлечь клиентов\"}"), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void checksReplacementAndInputBeforeCallingModel() throws Exception {
        var old = initialPlan();
        assertProblem(postGenerate(UUID.randomUUID(), "{\"goal\":\"Новая цель\"}"), 409, "PLAN_REPLACEMENT_REQUIRED");
        assertProblem(postGenerate(UUID.randomUUID(), replacementBody(old.id(), 99)), 409, "VERSION_CONFLICT");
        assertProblem(postGenerate(UUID.randomUUID(), "{\"goal\":false}"), 400, "MALFORMED_REQUEST");
        assertProblem(postGenerate(UUID.randomUUID(), "{\"goal\":\"   \"}"), 422, "VALIDATION_FAILED");
        assertProblem(postGenerate(UUID.randomUUID(), "{\"goal\":\"Новая цель\",\"replacePlan\":null}"), 400, "MALFORMED_REQUEST");
        verifyNoInteractions(model);
        assertThat(plans.getCurrent(owner).id()).isEqualTo(old.id());
    }

    @Test
    void failedGenerationKeepsOldPlanAndAllowsRetryWithSameKey() throws Exception {
        var old = initialPlan();
        UUID key = UUID.randomUUID();
        String body = replacementBody(old.id(), old.version());
        reset(model);
        doAnswer(invocation -> { throw new AssistantFailure("MODEL_UNAVAILABLE"); })
                .when(model).complete(anyString(), any(), anyMap(), anyInt(), any());
        assertProblem(postGenerate(key, body), 503, "SERVICE_UNAVAILABLE");
        assertThat(plans.getCurrent(owner).id()).isEqualTo(old.id());
        doAnswer(invocation -> planReply(json.valueToTree(invocation.getArgument(1))))
                .when(model).complete(anyString(), any(), anyMap(), anyInt(), any());
        assertThat(postGenerate(key, body).getResponse().getStatus()).isEqualTo(200);
        assertThat(plans.getCurrent(owner).id()).isNotEqualTo(old.id());
        assertThat(users.getById(owner).profileVersion()).isZero();
        assertThat(reminders.list(owner, null, 0, 20).total()).isZero();
    }

    @Test
    void inProgressRequestDoesNotStartSecondGeneration() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode request = json.readTree("{\"goal\":\"Привлечь клиентов\"}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return planReply(json.valueToTree(invocation.getArgument(1)));
        }).when(model).complete(anyString(), any(), anyMap(), anyInt(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> generation.generate(owner, key, request));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> generation.generate(owner, key, request)).isInstanceOfSatisfying(ActionException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REQUEST_IN_PROGRESS"));
            } finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS)).isNotNull();
        }
        verify(model, times(1)).complete(anyString(), any(), anyMap(), anyInt(), any());
    }

    @Test
    void callbacksUpdateProgressAndRemindersWithOwnershipAndVersions() {
        UUID planId = proposal("Составь план привлечения клиентов");
        assertThatThrownBy(() -> ui.callback(other, 42, "foreign", "ac:" + planId)).isInstanceOf(ActionException.class);
        ui.callback(owner, 42, "save", "ac:" + planId);
        var current = plans.getCurrent(owner);
        var stepButton = ui.plan(owner, 42).buttons().stream().filter(button -> button.callbackData() != null && button.callbackData().startsWith("sd:")).findFirst().orElseThrow();
        ui.callback(owner, 42, "step", stepButton.callbackData());
        assertThat(plans.getCurrent(owner).steps().getFirst().status()).isEqualTo(StepStatus.DONE);
        assertThatThrownBy(() -> ui.callback(owner, 42, "old", stepButton.callbackData())).isInstanceOf(PlanConflictException.class);
        assertThat(plans.getCurrent(owner).version()).isEqualTo(current.version() + 1);
        actions.confirm(owner, proposal("Напомни завтра в 9 по Москве продолжить курс"), false);
        var reminder = reminders.list(owner, ReminderStatus.ACTIVE, 0, 5).items().getFirst();
        ui.callback(owner, 42, "pause", "rp:" + reminder.id() + ":0");
        assertThat(reminders.getById(owner, reminder.id()).status()).isEqualTo(ReminderStatus.PAUSED);
        ui.callback(owner, 42, "resume", "rr:" + reminder.id() + ":1");
        assertThat(reminders.getById(owner, reminder.id()).status()).isEqualTo(ReminderStatus.ACTIVE);
        ui.callback(owner, 42, "cancel", "rx:" + reminder.id() + ":2");
        assertThat(reminders.getById(owner, reminder.id()).status()).isEqualTo(ReminderStatus.CANCELLED);
    }

    @Test
    void persistsButtonsAndDoesNotRegenerateOnDuplicateTelegramUpdate() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendMessage.class))).thenReturn(new Message());
        var handler = new TelegramUpdateHandler(users, conversations, ui);
        var processor = new TelegramUpdateProcessor(updates, handler, new TelegramReplySender(client), manager, clock);
        Update update = new Update();
        update.setUpdateId(123);
        Message message = new Message();
        message.setMessageId(123);
        message.setChat(Chat.builder().id(7_200_000_001L).type("private").build());
        message.setFrom(User.builder().id(7_200_000_001L).firstName("Action test").isBot(false).build());
        message.setText("Составь план привлечения клиентов");
        update.setMessage(message);
        processor.process(987, update);
        assertThat(updates.find(987, 123).reply().buttons()).hasSize(2);
        processor.recoverAfterRestart(987);
        processor.process(987, update);
        verify(model, times(1)).complete(anyString(), any(), anyMap(), anyInt(), any());
        verify(client, times(1)).execute(any(SendMessage.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.action_proposals", Long.class)).isEqualTo(1);
    }

    @Test
    void staleCallbackRollsBackBusinessWorkButStillPersistsAndDeliversNotice() throws Exception {
        var plan = initialPlan();
        var step = plan.steps().getFirst();
        plans.updateStep(owner, plan.id(), step.id(), plan.version(), StepStatus.DONE);
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(AnswerCallbackQuery.class))).thenReturn(true);
        var processor = new TelegramUpdateProcessor(updates, new TelegramUpdateHandler(users, conversations, ui),
                new TelegramReplySender(client), manager, clock);
        Message message = new Message();
        message.setMessageId(124);
        message.setChat(Chat.builder().id(7_200_000_001L).type("private").build());
        CallbackQuery callback = new CallbackQuery();
        callback.setId("old-step");
        callback.setFrom(User.builder().id(7_200_000_001L).firstName("Action test").isBot(false).build());
        callback.setMessage(message);
        callback.setData("sd:" + step.id() + ":0");
        Update update = new Update();
        update.setUpdateId(124);
        update.setCallbackQuery(callback);
        processor.process(987, update);
        processor.process(987, update);
        var stored = updates.find(987, 124);
        assertThat(stored.status().name()).isEqualTo("DONE");
        assertThat(stored.reply().callbackText()).contains("План изменился");
        assertThat(plans.getCurrent(owner).version()).isEqualTo(1);
        verify(client, times(1)).execute(any(AnswerCallbackQuery.class));
    }

    private UUID proposal(String message) {
        UUID id = UUID.randomUUID();
        assertThat(conversations.consult(owner, id, message)).doesNotContain("не удалось получить ответ");
        assertThat(proposals.find(owner, id)).isPresent();
        return id;
    }

    private AssistantModel.ModelReply planReply(JsonNode input) {
        var body = json.createObjectNode().put("status", "ANSWER").put("clarification", "");
        var steps = body.putArray("steps");
        int count = input.get("requiredSteps").intValue();
        for (int i = 0; i < count; i++) steps.addObject().put("title", "Действие " + (i + 1))
                .put("evidenceId", input.get("passages").get(i).get("id").stringValue());
        return new AssistantModel.ModelReply(body.toString(), "test-model", "a".repeat(64));
    }

    private com.alfatgbotik.plan.api.PlanDto initialPlan() {
        return plans.saveProposal(owner, new PlanProposal("Старая цель", List.of(
                new PlanProposal.Step("One", "Description", List.of(MATERIAL)), new PlanProposal.Step("Two", "Description", List.of(MATERIAL)),
                new PlanProposal.Step("Three", "Description", List.of(MATERIAL)))), Map.of(), null);
    }

    private String replacementBody(UUID id, long version) {
        return "{\"goal\":\"Новая цель\",\"replacePlan\":{\"id\":\"" + id + "\",\"expectedVersion\":" + version + "}}";
    }

    private MvcResult postGenerate(UUID key, String body) throws Exception {
        var request = post("/api/v1/plans/generate").header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType("application/json");
        if (body != null) request.content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) { return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)); }
    private void assertProblem(MvcResult result, int status, String code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(result).get("code").stringValue()).isEqualTo(code);
    }

    private void seedCatalog() {
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge.datasets", Long.class) > 0) return;
        Timestamp time = Timestamp.from(now.get());
        jdbc.update("""
                INSERT INTO knowledge.datasets (version,created_at,source_snapshot,materials_count,chunks_count,materials_sha256,chunks_sha256,imported_at)
                VALUES ('stage9-test',?,?,1,1,?,?,?)
                """, time, "sha256:" + "a".repeat(64), "a".repeat(64), "b".repeat(64), time);
        jdbc.update("""
                INSERT INTO knowledge.materials (dataset_version,id,source_type,source_id,title,description,url,is_paid,answer_allowed,
                    external_metadata_allowed,topics,industries,parent_material_ids)
                VALUES ('stage9-test',?,'ARTICLE',?,'Test article','Metadata','https://example.test/free',FALSE,TRUE,FALSE,'[]','[]','[]')
                """, MATERIAL, MATERIAL);
        String text = "Изучите потребности клиентов и соберите обратную связь, чтобы определить главные вопросы покупателей и выбрать полезное предложение для них.\n\n"
                + "Проверьте один канал привлечения клиентов и запишите результаты наблюдений, чтобы сравнить реакцию аудитории и улучшить выбранное предложение.\n\n"
                + "Оцените результаты проведённой проверки и выберите дальнейшее действие по собранным данным, сохранив выводы для следующего этапа работы.";
        jdbc.update("INSERT INTO knowledge.chunks (dataset_version,id,material_id,position,text,content_hash,external_text_allowed) VALUES ('stage9-test',?,?,1,?,?,FALSE)",
                CHUNK, MATERIAL, text, "a".repeat(64));
        UUID index = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge.search_indexes (id,dataset_version,model_name,model_revision,dimensions,format_version,vector_count,created_at)
                VALUES (?,'stage9-test','test',?,768,'embeddinggemma-v1',2,?)
                """, index, "a".repeat(64), time);
        jdbc.update("INSERT INTO knowledge.catalog_state (singleton,active_index_id) VALUES (TRUE,?)", index);
    }
}

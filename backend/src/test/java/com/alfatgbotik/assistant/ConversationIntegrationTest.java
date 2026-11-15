package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.persistence.ConversationRepository;
import com.alfatgbotik.assistant.retrieval.CatalogSearchResult;
import com.alfatgbotik.assistant.retrieval.CatalogSearchService;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.assistant.service.ConversationService;
import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.model.SearchHit;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.service.PlanService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"telegram.enabled=false", "assistant.timeout=2s"})
class ConversationIntegrationTest {

    private static final String DATABASE = "stage8_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PG = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("DB_PORT", "5432") + "/";
    private static final UUID MATERIAL = UUID.randomUUID();
    private static final UUID PAID = UUID.randomUUID();
    private static final UUID CHUNK = UUID.randomUUID();
    private static final UUID PAID_CHUNK = UUID.randomUUID();

    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired ConversationService conversations;
    @Autowired ConversationRepository repository;
    @Autowired UserService users;
    @Autowired PlanService plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired TelegramUpdateRepository telegramUpdates;
    @MockitoBean AssistantModel model;
    @MockitoBean CatalogSearchService search;

    private UUID owner;
    private UUID other;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        properties.add("spring.datasource.url", () -> PG + DATABASE + "?currentSchema=app,knowledge,public");
    }

    @AfterAll
    static void dropTestDatabase(@Autowired DataSource datasource, @Autowired org.quartz.Scheduler scheduler) throws Exception {
        scheduler.shutdown(true);
        datasource.unwrap(HikariDataSource.class).close();
        if (!DATABASE.matches("stage8_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test database name");
        }
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void prepare() {
        reset(model, search);
        jdbc.execute("TRUNCATE app.users CASCADE");
        seedCatalog();
        owner = users.getOrCreate(7_100_000_001L, "Conversation test").id();
        other = users.getOrCreate(7_100_000_002L, "Other test").id();
        when(search.search(anyString(), anyInt(), anyInt(), anyDouble())).thenReturn(new CatalogSearchResult(
                new CatalogIndex(UUID.randomUUID(), "stage8-test", new EmbeddingModelInfo("test", "a".repeat(64), 768), 4),
                List.of(new SearchHit(MATERIAL, CHUNK, 0.9), new SearchHit(PAID, PAID_CHUNK, 0.8)),
                List.of(new SearchHit(MATERIAL, null, 0.9), new SearchHit(PAID, null, 0.8))));
        when(model.generate(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return response(invocation.getArgument(0));
        });
    }

    @Test
    void persistsHistoryIsolatesUsersAndDoesNotPassPaidTextOrInternalFields() {
        reset(model);
        AtomicReference<AssistantContext> captured = new AtomicReference<>();
        when(model.generate(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            AssistantContext context = invocation.getArgument(0);
            captured.set(context);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(json.writeValueAsString(context)).doesNotContain("PRIVATE_PAID_CONTENT", "telegramUserId", "userId", "recommendationReason");
            assertThat(context.sources()).extracting(AssistantContext.Source::chunkId).containsExactly(CHUNK);
            return response(context);
        });
        String reply = conversations.consult(owner, UUID.randomUUID(), "Как привлечь покупателей?");
        assertThat(reply).contains("Ответ по источнику", "https://example.test/free");
        conversations.consult(owner, UUID.randomUUID(), "А если бюджет небольшой?");
        assertThat(captured.get().history()).hasSize(2);
        assertThat(captured.get().history().getFirst().text()).isEqualTo("Как привлечь покупателей?");
        conversations.consult(other, UUID.randomUUID(), "Что посоветуешь?");
        assertThat(captured.get().history()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.conversation_turns WHERE status='COMPLETED'", Long.class)).isEqualTo(3);
    }

    @Test
    void usesPreviousQuestionForFollowUpButDoesNotMixIndependentNewTopic() {
        conversations.consult(owner, UUID.randomUUID(), "Что такое точка безубыточности?");
        conversations.consult(owner, UUID.randomUUID(), "А как это применить?");
        verify(search).search("Что такое точка безубыточности?\nА как это применить?", 5, 3, 0.35);
        conversations.consult(owner, UUID.randomUUID(), "Как выбрать налоговый режим?");
        verify(search).search("Как выбрать налоговый режим?", 5, 3, 0.35);
    }

    @Test
    void repeatsCompletedRequestWithoutCallingModelAndKeepsBoundedHistoryMessages() {
        UUID id = UUID.randomUUID();
        String first = conversations.consult(owner, id, "Первый вопрос");
        assertThat(conversations.consult(owner, id, "Первый вопрос")).isEqualTo(first);
        verify(model, times(1)).generate(any(), nullable(String.class), any());
        conversations.consult(owner, UUID.randomUUID(), "Второй вопрос");
        conversations.consult(owner, UUID.randomUUID(), "Третий вопрос");
        var history = repository.history(owner);
        assertThat(history).hasSize(6);
        assertThat(history.getFirst().text()).isEqualTo("Первый вопрос");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.conversation_turns", Long.class)).isEqualTo(3);
    }

    @Test
    void repairsInvalidFormatOnceAndFailsAfterSecondInvalidResponse() {
        reset(model);
        when(model.generate(any(), nullable(String.class), any())).thenReturn(new AssistantModel.ModelReply("bad", "test", "a".repeat(64)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        assertThat(conversations.consult(owner, UUID.randomUUID(), "Вопрос для исправления")).contains("Ответ по источнику");
        verify(model, times(2)).generate(any(), nullable(String.class), any());
        reset(model);
        when(model.generate(any(), nullable(String.class), any())).thenReturn(new AssistantModel.ModelReply("bad", "test", "a".repeat(64)));
        UUID failed = UUID.randomUUID();
        assertThat(conversations.consult(owner, failed, "Ещё один вопрос")).contains("не удалось получить ответ");
        verify(model, times(2)).generate(any(), nullable(String.class), any());
        assertThat(jdbc.queryForObject("SELECT status FROM app.conversation_turns WHERE id=?", String.class, failed)).isEqualTo("FAILED");
    }

    @Test
    void preservesTechnicalFailureAndDoesNotRegenerateFailedDuplicate() {
        reset(model);
        when(model.generate(any(), nullable(String.class), any())).thenThrow(new AssistantFailure("MODEL_UNAVAILABLE"));
        UUID id = UUID.randomUUID();
        String reply = conversations.consult(owner, id, "Вопрос при сбое");
        assertThat(reply).contains("не удалось получить ответ").doesNotContain("не нашлось");
        assertThat(conversations.consult(owner, id, "Вопрос при сбое")).isEqualTo(reply);
        verify(model, times(1)).generate(any(), nullable(String.class), any());
        assertThat(repository.history(owner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT error_code FROM app.conversation_turns WHERE id=?", String.class, id)).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void cancelsLongGenerationAtSharedDeadlineWithoutLateSuccessfulWrite() {
        reset(model);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(model.generate(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            }
            return response(invocation.getArgument(0));
        });
        UUID id = UUID.randomUUID();
        long started = System.nanoTime();
        assertThat(conversations.consult(owner, id, "Долгий вопрос")).contains("не удалось получить ответ");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3500);
        assertThat(interrupted.get()).isTrue();
        assertThat(jdbc.queryForObject("SELECT error_code FROM app.conversation_turns WHERE id=?", String.class, id)).isEqualTo("TIMEOUT");
    }

    @Test
    void serializesSameUserRequestsAndPassesFirstAnswerToSecondRequest() throws Exception {
        reset(model);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<List<AssistantContext.HistoryMessage>> secondHistory = new AtomicReference<>();
        when(model.generate(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            AssistantContext context = invocation.getArgument(0);
            if (context.message().equals("Первый")) {
                entered.countDown();
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            } else {
                secondHistory.set(context.history());
            }
            return response(context);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> conversations.consult(owner, UUID.randomUUID(), "Первый"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> conversations.consult(owner, UUID.randomUUID(), "Второй"));
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).contains("Ответ по источнику");
            assertThat(second.get(3, TimeUnit.SECONDS)).contains("Ответ по источнику");
            assertThat(secondHistory.get()).hasSize(2);
            assertThat(secondHistory.get().getFirst().text()).isEqualTo("Первый");
        }
    }

    @Test
    void sendsTelegramReplyOnceAndRecoversInterruptedTurnWithoutRegeneration() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new Message();
        });
        var handler = new TelegramUpdateHandler(users, conversations, context.getBean(com.alfatgbotik.telegram.TelegramActions.class));
        var processor = new TelegramUpdateProcessor(telegramUpdates, handler, new TelegramReplySender(client), manager, Clock.systemUTC());
        Update update = new Update();
        update.setUpdateId(100);
        Message message = new Message();
        message.setMessageId(100);
        message.setChat(Chat.builder().id(7_100_000_001L).type("private").build());
        message.setFrom(User.builder().id(7_100_000_001L).firstName("Conversation test").isBot(false).build());
        message.setText("Как привлечь покупателей?");
        update.setMessage(message);
        processor.process(999, update);
        processor.recoverAfterRestart(999);
        processor.process(999, update);
        verify(model, times(1)).generate(any(), nullable(String.class), any());
        verify(client, times(1)).execute(any(SendMessage.class));
        reset(model);
        UUID interrupted = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> repository.begin(owner, interrupted, "Прерванный вопрос", Instant.now()));
        processor.recoverAfterRestart(999);
        assertThat(conversations.consult(owner, interrupted, "Прерванный вопрос")).contains("не удалось получить ответ");
        verifyNoInteractions(model);
    }

    @Test
    void passesCurrentPlanWithoutRecommendationReasonsAndDoesNotApplyModelChanges() {
        reset(model);
        plans.saveProposal(owner, new PlanProposal("Test goal", List.of(
                new PlanProposal.Step("First", "Description", List.of(MATERIAL)),
                new PlanProposal.Step("Second", "Description", List.of(MATERIAL)),
                new PlanProposal.Step("Third", "Description", List.of(MATERIAL)))), Map.of(MATERIAL, "PRIVATE_REASON"), null);
        when(model.generate(any(), nullable(String.class), any())).thenAnswer(invocation -> {
            AssistantContext context = invocation.getArgument(0);
            assertThat(context.currentPlan()).isNotNull();
            assertThat(json.writeValueAsString(context)).doesNotContain("recommendationReason", "PRIVATE_REASON");
            var normal = response(context);
            return new AssistantModel.ModelReply(normal.text().replace("\"profilePatch\":{}", "\"profilePatch\":{\"industry\":\"кофейня\"}"), "test", "a".repeat(64));
        });
        assertThat(conversations.consult(owner, UUID.randomUUID(), "Вопрос по плану")).contains("не удалось получить ответ");
        assertThat(users.getById(owner).profile().industry()).isNull();
        assertThat(users.getById(owner).profileVersion()).isZero();
    }

    private AssistantModel.ModelReply response(AssistantContext context) {
        return new AssistantModel.ModelReply("""
                {"schemaVersion":"0.1.0","status":"ANSWER","answer":"Ответ по источнику",
                "sourceChunkIds":["%s"],"recommendations":[],"profilePatch":{},"planProposal":null,"reminderProposal":null}
                """.formatted(context.sources().getFirst().chunkId()), "test-model", "a".repeat(64));
    }

    private void seedCatalog() {
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge.datasets", Long.class) > 0) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO knowledge.datasets (version, created_at, source_snapshot, materials_count, chunks_count,
                    materials_sha256, chunks_sha256, imported_at) VALUES ('stage8-test', ?, ?, 2, 2, ?, ?, ?)
                """, now, "sha256:" + "a".repeat(64), "a".repeat(64), "b".repeat(64), now);
        for (UUID id : List.of(MATERIAL, PAID)) {
            boolean paid = id.equals(PAID);
            jdbc.update("""
                    INSERT INTO knowledge.materials (dataset_version, id, source_type, source_id, title, description, url,
                        is_paid, answer_allowed, external_metadata_allowed, topics, industries, parent_material_ids)
                    VALUES ('stage8-test', ?, 'COURSE', ?, 'Test course', 'Public metadata', ?, ?, ?, FALSE, '[]', '[]', '[]')
                    """, id, id, paid ? "https://example.test/paid" : "https://example.test/free", paid, !paid);
            jdbc.update("""
                    INSERT INTO knowledge.chunks (dataset_version, id, material_id, position, text, content_hash, external_text_allowed)
                    VALUES ('stage8-test', ?, ?, 1, ?, ?, FALSE)
                    """, paid ? PAID_CHUNK : CHUNK, id, paid ? "PRIVATE_PAID_CONTENT must never enter model input." : "Разрешённый материал о привлечении покупателей и развитии бизнеса.", "a".repeat(64));
        }
        UUID index = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge.search_indexes (id, dataset_version, model_name, model_revision, dimensions,
                    format_version, vector_count, created_at) VALUES (?, 'stage8-test', 'test', ?, 768, 'embeddinggemma-v1', 4, ?)
                """, index, "a".repeat(64), now);
        jdbc.update("INSERT INTO knowledge.catalog_state (singleton, active_index_id) VALUES (TRUE, ?)", index);
    }
}

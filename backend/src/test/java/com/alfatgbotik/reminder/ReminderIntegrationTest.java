package com.alfatgbotik.reminder;

import com.alfatgbotik.common.error.RequestIdFilter;
import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.reminder.api.ReminderCreation;
import com.alfatgbotik.reminder.api.ReminderDto;
import com.alfatgbotik.reminder.api.ReminderRequests;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.persistence.ReminderDeliveryRepository;
import com.alfatgbotik.reminder.persistence.ReminderRepository;
import com.alfatgbotik.reminder.scheduling.ReminderDispatchJob;
import com.alfatgbotik.reminder.service.ReminderDeliveryService;
import com.alfatgbotik.reminder.service.ReminderException;
import com.alfatgbotik.reminder.service.ReminderService;
import com.alfatgbotik.reminder.service.ReminderUpdateCommand;
import com.alfatgbotik.telegram.TelegramReplySender;
import com.alfatgbotik.telegram.model.TelegramDeliveryResult;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "telegram.enabled=false")
class ReminderIntegrationTest {

    private static final String DATABASE = "stage7_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PG = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("DB_PORT", "5432") + "/";
    private static final UUID MATERIAL = UUID.randomUUID();
    private static final UUID OTHER_MATERIAL = UUID.randomUUID();

    @Autowired ReminderService reminders;
    @Autowired ReminderCreation creation;
    @Autowired ReminderDeliveryService dispatcher;
    @Autowired ReminderDeliveryRepository deliveries;
    @Autowired ReminderRepository repository;
    @Autowired UserService users;
    @Autowired PlanService plans;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired RequestIdFilter requestIds;
    @Autowired WebApplicationContext context;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean TelegramReplySender sender;
    @MockitoBean Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private UUID owner;
    private UUID other;
    private long telegramId;
    private String token;
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
    static void dropTestDatabase(@Autowired DataSource datasource, @Autowired org.quartz.Scheduler scheduler) throws Exception {
        scheduler.shutdown(true);
        datasource.unwrap(HikariDataSource.class).close();
        if (!DATABASE.matches("stage7_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test database name");
        }
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void prepare() {
        reset(sender, clock);
        now.set(Instant.parse("2026-11-15T10:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(sender.send(any())).thenReturn(TelegramDeliveryResult.delivered());
        jdbc.execute("TRUNCATE app.users CASCADE");
        seedCatalog();
        telegramId = 7_000_000_001L;
        owner = users.confirmTelegramChat(telegramId, telegramId, "Reminder test").id();
        other = users.getOrCreate(7_000_000_002L, "Other test").id();
        token = sessions.issue(owner).accessToken();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
    }

    @Test
    void exposesOwnedApiWithPaginationVersionsAndTerminalRules() throws Exception {
        JsonNode request = onceBody(now.get().plusSeconds(60));
        var created = mvc.perform(post("/api/v1/reminders").header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(request.toString())).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = body(created);
        UUID id = UUID.fromString(body.get("id").stringValue());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "title", "status", "schedule", "nextFireAt",
                "lastSentAt", "completionReason", "material", "planStepId", "version", "createdAt", "updatedAt");
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/reminders/" + id);
        assertThat(body(mvc.perform(get("/api/v1/reminders?status=ACTIVE&offset=0&limit=1")
                .header("Authorization", bearer())).andReturn()).get("total").longValue()).isEqualTo(1);
        assertProblem(mvc.perform(get("/api/v1/reminders/" + id)).andReturn(), 401, "AUTH_REQUIRED");
        String foreign = sessions.issue(other).accessToken();
        assertProblem(mvc.perform(get("/api/v1/reminders/" + id)
                .header("Authorization", "Bearer " + foreign)).andReturn(), 404, "NOT_FOUND");
        assertProblem(mvc.perform(patch("/api/v1/reminders/" + id).header("Authorization", bearer())
                .contentType("application/json").content("{\"expectedVersion\":1,\"status\":\"PAUSED\"}"))
                .andReturn(), 409, "VERSION_CONFLICT");
        assertThat(mvc.perform(patch("/api/v1/reminders/" + id).header("Authorization", bearer())
                .contentType("application/json").content("{\"expectedVersion\":0,\"status\":\"CANCELLED\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertProblem(mvc.perform(patch("/api/v1/reminders/" + id).header("Authorization", bearer())
                .contentType("application/json").content("{\"expectedVersion\":1,\"status\":\"ACTIVE\"}"))
                .andReturn(), 409, "REMINDER_TERMINAL");
        assertProblem(mvc.perform(get("/api/v1/reminders?limit=101").header("Authorization", bearer()))
                .andReturn(), 422, "VALIDATION_FAILED");
        assertProblem(mvc.perform(post("/api/v1/reminders").header("Authorization", bearer())
                .contentType("application/json").content(request.toString())).andReturn(), 400, "MALFORMED_REQUEST");
    }

    @Test
    void replaysOriginalResultBeforeStateChecksAndAllowsRetryAfterFailure() {
        UUID key = UUID.randomUUID();
        JsonNode request = onceBody(now.get().plusSeconds(60));
        ReminderDto first = creation.create(owner, key, request);
        reminders.update(owner, first.id(), new ReminderUpdateCommand(0, null, null, ReminderStatus.CANCELLED));
        jdbc.update("UPDATE app.users SET can_receive_reminders = false WHERE id = ?", owner);
        now.set(now.get().plusSeconds(3600));
        assertThat(creation.create(owner, key, request)).isEqualTo(first);
        JsonNode changed = json.readTree(request.toString().replace("Test reminder", "Different reminder"));
        assertThatThrownBy(() -> creation.create(owner, key, changed)).isInstanceOfSatisfying(ReminderException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        UUID failedKey = UUID.randomUUID();
        JsonNode future = onceBody(now.get().plusSeconds(60));
        assertThatThrownBy(() -> creation.create(owner, failedKey, future)).isInstanceOfSatisfying(ReminderException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("BOT_START_REQUIRED"));
        users.confirmTelegramChat(telegramId, telegramId, "Reminder test");
        assertThat(creation.create(owner, failedKey, future).status()).isEqualTo(ReminderStatus.ACTIVE);
        assertThat(repository.count(owner, null)).isEqualTo(2);
    }

    @Test
    void reportsInProgressWithoutWaitingForConcurrentCommand() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(repository.tryLockCommand(owner, key)).isTrue();
                locked.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> creation.create(owner, key, onceBody(now.get().plusSeconds(60))))
                        .isInstanceOfSatisfying(ReminderException.class,
                                exception -> assertThat(exception.getCode()).isEqualTo("REQUEST_IN_PROGRESS"));
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void validatesMaterialAndStepLinksAndKeepsReminderAfterPlanArchive() throws Exception {
        var proposal = new PlanProposal("Test goal", List.of(
                new PlanProposal.Step("First", "First description", List.of(MATERIAL)),
                new PlanProposal.Step("Second", "Second description", List.of(MATERIAL)),
                new PlanProposal.Step("Third", "Third description", List.of(MATERIAL))));
        var plan = plans.saveProposal(owner, proposal, Map.of(MATERIAL, "For this goal"), null);
        UUID step = plan.steps().getFirst().id();
        var request = ReminderRequests.create(onceBody(now.get().plusSeconds(60)));
        var linked = reminders.create(owner, new com.alfatgbotik.reminder.service.ReminderCreateCommand(
                request.title(), request.schedule(), MATERIAL, step));
        assertThat(linked.material().recommendationReason()).isNull();
        plans.archive(owner, plan.id(), 0);
        assertThat(reminders.getById(owner, linked.id()).status()).isEqualTo(ReminderStatus.ACTIVE);
        assertThatThrownBy(() -> reminders.create(other, new com.alfatgbotik.reminder.service.ReminderCreateCommand(
                "Foreign", request.schedule(), MATERIAL, step))).isInstanceOf(ReminderException.class);
        var invalid = json.valueToTree(Map.of("title", "Mismatch", "schedule", request.schedule(),
                "materialId", OTHER_MATERIAL, "planStepId", step));
        assertProblem(mvc.perform(post("/api/v1/reminders").header("Authorization", bearer())
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content(invalid.toString()))
                .andReturn(), 422, "VALIDATION_FAILED");
    }

    @Test
    void deliversOnceAndNeverRepeatsCompletedOccurrence() {
        ReminderDto reminder = createOnce(60);
        now.set(now.get().plusSeconds(61));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        ReminderDto result = reminders.getById(owner, reminder.id());
        assertThat(result.status()).isEqualTo(ReminderStatus.COMPLETED);
        assertThat(result.completionReason()).isEqualTo("DELIVERED");
        assertThat(result.lastSentAt()).isEqualTo(now.get());
        assertThat(result.nextFireAt()).isNull();
        verify(sender, times(1)).send(any());
    }

    @Test
    void pausesReschedulesResumesAndCancelsWithoutSendingOldSchedule() {
        ReminderDto reminder = createOnce(60);
        reminders.update(owner, reminder.id(), new ReminderUpdateCommand(0, null, null, ReminderStatus.PAUSED));
        now.set(now.get().plusSeconds(120));
        dispatcher.dispatchDue();
        verify(sender, times(0)).send(any());
        assertThatThrownBy(() -> reminders.update(owner, reminder.id(),
                new ReminderUpdateCommand(1, null, null, ReminderStatus.ACTIVE))).isInstanceOf(RuntimeException.class);
        var future = ReminderRequests.create(onceBody(now.get().plusSeconds(60))).schedule();
        ReminderDto resumed = reminders.update(owner, reminder.id(), new ReminderUpdateCommand(1, "Moved", future, ReminderStatus.ACTIVE));
        assertThat(resumed.version()).isEqualTo(2);
        reminders.update(owner, reminder.id(), new ReminderUpdateCommand(2, null, null, ReminderStatus.CANCELLED));
        now.set(now.get().plusSeconds(120));
        dispatcher.dispatchDue();
        verify(sender, times(0)).send(any());
    }

    @Test
    void catchesUpOnceWithin24HoursAndExpiresOlderOnce() {
        ReminderDto recent = createOnce(60);
        now.set(now.get().plusSeconds(86460));
        dispatcher.dispatchDue();
        verify(sender).send(org.mockito.ArgumentMatchers.argThat(reply -> reply.text().contains("с опозданием")));
        assertThat(reminders.getById(owner, recent.id()).completionReason()).isEqualTo("DELIVERED");
        clearInvocations(sender);
        ReminderDto old = createOnce(60);
        now.set(now.get().plusSeconds(86461));
        dispatcher.dispatchDue();
        assertThat(reminders.getById(owner, old.id()).completionReason()).isEqualTo("EXPIRED");
        verify(sender, times(0)).send(any());
    }

    @Test
    void recurringSendsOnlyOneCatchUpAndUsesFutureLocalRule() {
        JsonNode request = json.readTree("""
                {"title":"Daily", "schedule":{"type":"DAILY","localTime":"15:00","startDate":"2026-11-15",
                 "timeZone":"Europe/Moscow"},"materialId":null,"planStepId":null}
                """);
        ReminderDto reminder = creation.create(owner, UUID.randomUUID(), request);
        assertThat(json.valueToTree(reminder).get("schedule").get("localTime").stringValue()).isEqualTo("15:00");
        now.set(Instant.parse("2026-11-20T13:00:00Z"));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        assertThat(reminders.getById(owner, reminder.id()).nextFireAt()).isEqualTo(Instant.parse("2026-11-21T12:00:00Z"));
        assertThat(reminders.getById(owner, reminder.id()).completionReason()).isNull();
        verify(sender, times(1)).send(any());
    }

    @Test
    void skipsOldWeeklyOccurrenceWithoutSendingBacklog() {
        JsonNode request = json.readTree("""
                {"title":"Weekly", "schedule":{"type":"WEEKLY","localTime":"12:00","startDate":"2026-11-15",
                 "timeZone":"UTC","dayOfWeek":"MONDAY"},"materialId":null,"planStepId":null}
                """);
        ReminderDto reminder = creation.create(owner, UUID.randomUUID(), request);
        now.set(Instant.parse("2026-11-20T13:00:00Z"));
        dispatcher.dispatchDue();
        assertThat(reminders.getById(owner, reminder.id()).nextFireAt()).isEqualTo(Instant.parse("2026-11-23T12:00:00Z"));
        verify(sender, times(0)).send(any());
    }

    @Test
    void limitsExplicitRetriesToThreeAttemptsAndHonorsRetryAfter() {
        when(sender.send(any())).thenReturn(TelegramDeliveryResult.retry("API_429", Duration.ofSeconds(10)));
        ReminderDto reminder = createOnce(60);
        now.set(now.get().plusSeconds(61));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        verify(sender, times(1)).send(any());
        now.set(now.get().plusSeconds(10));
        dispatcher.dispatchDue();
        now.set(now.get().plusSeconds(10));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        verify(sender, times(3)).send(any());
        assertThat(reminders.getById(owner, reminder.id()).status()).isEqualTo(ReminderStatus.PAUSED);
    }

    @Test
    void blockedBotDisablesContactAndPausesAllActiveReminders() {
        when(sender.send(any())).thenReturn(TelegramDeliveryResult.failed("API_403"));
        createOnce(60);
        ReminderDto later = createOnce(3600);
        now.set(now.get().plusSeconds(61));
        dispatcher.dispatchDue();
        assertThat(users.getById(owner).canReceiveReminders()).isFalse();
        assertThat(repository.list(owner, ReminderStatus.PAUSED, 0, 100)).hasSize(2);
        assertThatThrownBy(() -> reminders.update(owner, later.id(),
                new ReminderUpdateCommand(1, null, null, ReminderStatus.ACTIVE)))
                .isInstanceOfSatisfying(ReminderException.class, exception -> assertThat(exception.getCode()).isEqualTo("BOT_START_REQUIRED"));
    }

    @Test
    void ambiguousSendCompletesOnceAndContinuesRecurringWithoutRetry() {
        when(sender.send(any())).thenReturn(TelegramDeliveryResult.unknown());
        ReminderDto once = createOnce(60);
        ReminderDto recurring = creation.create(owner, UUID.randomUUID(), json.readTree("""
                {"title":"Daily", "schedule":{"type":"DAILY","localTime":"10:01","startDate":"2026-11-15",
                 "timeZone":"UTC"},"materialId":null,"planStepId":null}
                """));
        now.set(now.get().plusSeconds(61));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        assertThat(reminders.getById(owner, once.id()).completionReason()).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(reminders.getById(owner, recurring.id()).nextFireAt()).isEqualTo(Instant.parse("2026-11-16T10:01:00Z"));
        verify(sender, times(2)).send(any());
    }

    @Test
    void recoversInterruptedSendAsUnknownAndSkipsStalePendingVersion() {
        ReminderDto interrupted = createOnce(60);
        ReminderDto stale = createOnce(60);
        now.set(now.get().plusSeconds(61));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UUID id = deliveries.insert(interrupted.id(), 0, interrupted.nextFireAt(), interrupted.nextFireAt(), now.get());
            deliveries.sending(id, now.get());
            deliveries.insert(stale.id(), 0, stale.nextFireAt(), stale.nextFireAt(), now.get());
        });
        reminders.update(owner, stale.id(), new ReminderUpdateCommand(0, null, null, ReminderStatus.CANCELLED));
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        assertThat(reminders.getById(owner, interrupted.id()).completionReason()).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(reminders.getById(owner, stale.id()).status()).isEqualTo(ReminderStatus.CANCELLED);
        verify(sender, times(0)).send(any());
    }

    @Test
    void cancellationDuringAcceptedSendKeepsCancellationAndRecordsDelivery() {
        ReminderDto reminder = createOnce(60);
        when(sender.send(any())).thenAnswer(invocation -> {
            reminders.update(owner, reminder.id(), new ReminderUpdateCommand(0, null, null, ReminderStatus.CANCELLED));
            return TelegramDeliveryResult.delivered();
        });
        now.set(now.get().plusSeconds(61));
        dispatcher.dispatchDue();
        ReminderDto result = reminders.getById(owner, reminder.id());
        assertThat(result.status()).isEqualTo(ReminderStatus.CANCELLED);
        assertThat(result.lastSentAt()).isEqualTo(now.get());
        assertThat(result.completionReason()).isNull();
    }

    @Test
    void restoresJdbcQuartzTriggerAndExecutesInjectedDispatchJob() throws Exception {
        ReminderDto reminder = createOnce(60);
        now.set(now.get().plusSeconds(61));
        SchedulerFactoryBean first = quartzFactory();
        first.setJobDetails(JobBuilder.newJob(ReminderDispatchJob.class).withIdentity("restore", "test").storeDurably().build());
        first.setTriggers(TriggerBuilder.newTrigger().withIdentity("restore", "test").forJob("restore", "test")
                .startAt(new Date(System.currentTimeMillis() - 1000))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow()).build());
        first.afterPropertiesSet();
        assertThat(first.getScheduler().getMetaData().isJobStoreSupportsPersistence()).isTrue();
        first.destroy();
        SchedulerFactoryBean restored = quartzFactory();
        restored.afterPropertiesSet();
        try {
            assertThat(restored.getScheduler().getJobDetail(new JobKey("restore", "test"))).isNotNull();
            restored.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(reminders.getById(owner, reminder.id()).completionReason()).isEqualTo("DELIVERED"));
            verify(sender, times(1)).send(any());
        } finally {
            restored.destroy();
        }
    }

    private SchedulerFactoryBean quartzFactory() {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setDataSource(datasource);
        factory.setSchedulerName("stage7-persistence");
        factory.setAutoStartup(false);
        factory.setWaitForJobsToCompleteOnShutdown(true);
        Properties properties = new Properties();
        properties.setProperty("org.quartz.jobStore.tablePrefix", "app.qrtz_");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        factory.setQuartzProperties(properties);
        SpringBeanJobFactory jobs = new SpringBeanJobFactory();
        jobs.setApplicationContext(context);
        factory.setJobFactory(jobs);
        return factory;
    }

    private ReminderDto createOnce(long seconds) {
        return creation.create(owner, UUID.randomUUID(), onceBody(now.get().plusSeconds(seconds)));
    }

    private JsonNode onceBody(Instant at) {
        return json.readTree("""
                {"title":"Test reminder","schedule":{"type":"ONCE","at":"%s","timeZone":"Europe/Moscow"},
                 "materialId":null,"planStepId":null}
                """.formatted(at));
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private JsonNode body(MvcResult result) {
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private void assertProblem(MvcResult result, int status, String code) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body(result).get("code").stringValue()).isEqualTo(code);
    }

    private void seedCatalog() {
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge.datasets", Long.class) > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO knowledge.datasets (version, created_at, source_snapshot, materials_count, chunks_count,
                    materials_sha256, chunks_sha256, imported_at) VALUES ('stage7-test', ?, ?, 2, 1, ?, ?, ?)
                """, Timestamp.from(now.get()), "sha256:" + "a".repeat(64), "a".repeat(64), "b".repeat(64), Timestamp.from(now.get()));
        for (UUID id : List.of(MATERIAL, OTHER_MATERIAL)) {
            jdbc.update("""
                    INSERT INTO knowledge.materials (dataset_version, id, source_type, source_id, title, url, is_paid,
                        answer_allowed, external_metadata_allowed, topics, industries, parent_material_ids)
                    VALUES ('stage7-test', ?, 'COURSE', ?, 'Test course', 'https://example.test/course', FALSE,
                            TRUE, FALSE, '[]', '[]', '[]')
                    """, id, id);
        }
        UUID index = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge.search_indexes (id, dataset_version, model_name, model_revision, dimensions,
                    format_version, vector_count, created_at)
                VALUES (?, 'stage7-test', 'test', ?, 768, 'embeddinggemma-v1', 2, ?)
                """, index, "sha256:" + "a".repeat(64), Timestamp.from(now.get()));
        jdbc.update("INSERT INTO knowledge.catalog_state (singleton, active_index_id) VALUES (TRUE, ?)", index);
    }
}

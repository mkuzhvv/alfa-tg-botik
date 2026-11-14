package com.alfatgbotik.identity.auth;

import com.alfatgbotik.identity.service.SessionService;
import com.alfatgbotik.common.error.RequestIdFilter;
import com.alfatgbotik.identity.persistence.AppUserRepository;
import com.alfatgbotik.identity.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"telegram.enabled=false", "telegram.token=stage4-test-bot-token"})
@Import(AuthApiIntegrationTest.TimeConfiguration.class)
class AuthApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JsonMapper json;
    @Autowired
    private Clock clock;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserService userService;
    @Autowired
    private SessionService sessions;
    @Autowired
    private RequestIdFilter requestIds;

    private final Set<Long> testTelegramIds = new HashSet<>();
    private final List<String> testTokens = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        when(clock.instant()).thenReturn(TelegramInitDataValidatorTest.NOW);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
    }

    @AfterEach
    void deleteTestData() {
        for (String token : testTokens) {
            sessions.find(token).ifPresent(sessions::revoke);
        }
        for (Long telegramId : testTelegramIds) {
            users.findByTelegramUserId(telegramId).ifPresent(users::delete);
        }
    }

    @Test
    void loginReturnsContractUserAndSessionWithoutConfirmingBotChat() throws Exception {
        long telegramId = newTelegramId();
        JsonNode login = login(telegramId);

        assertThat(login.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt", "user");
        assertThat(login.get("tokenType").stringValue()).isEqualTo("Bearer");
        assertThat(login.get("expiresAt").stringValue())
                .isEqualTo(TelegramInitDataValidatorTest.NOW.plusSeconds(8 * 3600).toString());
        JsonNode user = login.get("user");
        assertThat(user.propertyNames()).containsExactlyInAnyOrder("id", "displayName", "canReceiveReminders",
                "profileVersion", "profile");
        assertThat(user.get("canReceiveReminders").booleanValue()).isFalse();
        assertThat(user.get("profileVersion").longValue()).isZero();
        assertThat(user.get("profile").propertyNames()).hasSize(6);
        assertThat(user.get("profile").get("industry").isNull()).isTrue();

        MvcResult loaded = mvc.perform(get("/api/v1/me").header("Authorization", bearer(login))).andReturn();
        assertThat(loaded.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(loaded.getResponse().getContentAsString(StandardCharsets.UTF_8))).isEqualTo(user);
        assertThat(users.findByTelegramUserId(telegramId).orElseThrow().getTelegramChatId()).isNull();
    }

    @Test
    void repeatedLoginCreatesIndependentSessionsAndLogoutRevokesOnlyCurrentToken() throws Exception {
        long telegramId = newTelegramId();
        JsonNode first = login(telegramId);
        JsonNode second = login(telegramId);
        assertThat(first.get("user").get("id")).isEqualTo(second.get("user").get("id"));
        assertThat(users.countByTelegramUserId(telegramId)).isEqualTo(1);

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(first))).andReturn();
        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(logout.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEmpty();
        assertProblem(mvc.perform(get("/api/v1/me").header("Authorization", bearer(first))).andReturn(),
                401, "AUTH_REQUIRED");
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", bearer(second)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiresSessionAndLeavesHealthPublic() throws Exception {
        JsonNode login = login(newTelegramId());
        when(clock.instant()).thenReturn(TelegramInitDataValidatorTest.NOW.plusSeconds(8 * 3600));

        assertProblem(mvc.perform(get("/api/v1/me").header("Authorization", bearer(login))).andReturn(),
                401, "AUTH_REQUIRED");
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void updatesProfileAndDistinguishesMissingFieldFromExplicitNull() throws Exception {
        JsonNode login = login(newTelegramId());
        String token = bearer(login);
        MvcResult changed = mvc.perform(patch("/api/v1/me/profile").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"expectedVersion":0,"businessStage":"LAUNCH","industry":"Кофейня",
                         "experience":"BEGINNER","currentTask":"Увеличить продажи",
                         "taxRegime":"USN_6","timeZone":"Europe/Moscow"}
                        """)).andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        JsonNode first = json.readTree(changed.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.get("profileVersion").longValue()).isEqualTo(1);
        assertThat(first.get("profile").get("industry").stringValue()).isEqualTo("Кофейня");

        MvcResult cleared = mvc.perform(patch("/api/v1/me/profile").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"industry\":null}"))
                .andReturn();
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        JsonNode second = json.readTree(cleared.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(second.get("profileVersion").longValue()).isEqualTo(2);
        assertThat(second.get("profile").get("industry").isNull()).isTrue();
        assertThat(second.get("profile").get("currentTask").stringValue()).isEqualTo("Увеличить продажи");

        assertProblem(mvc.perform(patch("/api/v1/me/profile").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"industry\":\"stale\"}"))
                .andReturn(), 409, "VERSION_CONFLICT");
    }

    @Test
    void neverUsesClientSuppliedUserIdToSelectProfile() throws Exception {
        JsonNode first = login(newTelegramId());
        JsonNode second = login(newTelegramId());
        String secondId = second.get("user").get("id").stringValue();

        assertThat(mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"industry\":\"Retail\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        MvcResult secondProfile = mvc.perform(get("/api/v1/me").header("Authorization", bearer(second))).andReturn();
        assertThat(json.readTree(secondProfile.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("profile").get("industry").isNull()).isTrue();

        MvcResult response = mvc.perform(get("/api/v1/me").param("userId", secondId)
                .header("Authorization", bearer(first))).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id"))
                .isEqualTo(first.get("user").get("id"));
        assertProblem(mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(first))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("expectedVersion", 0, "industry", "forged", "userId", secondId))))
                .andReturn(), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(get("/api/v1/users/" + secondId).header("Authorization", bearer(first)))
                .andReturn(), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"expectedVersion\":\"0\",\"industry\":\"Retail\"}",
            "{\"expectedVersion\":0.5,\"industry\":\"Retail\"}",
            "{\"expectedVersion\":true,\"industry\":\"Retail\"}",
            "{\"expectedVersion\":9223372036854775808,\"industry\":\"Retail\"}",
            "{\"expectedVersion\":0,\"industry\":42}",
            "{\"expectedVersion\":0,\"taxRegime\":1}",
            "{\"expectedVersion\":0,\"experience\":false}",
            "{\"expectedVersion\":0,\"industry\":[]}",
            "{\"expectedVersion\":0,\"industry\":{}}",
            "{\"expectedVersion\":0,\"unexpected\":null}",
            "{\"expectedVersion\":0,\"industry\":\"one\",\"industry\":\"two\"}",
            "{\"expectedVersion\":0,\"industry\":\"one\"} {}", "[]", "null", "{"
    })
    void rejectsMalformedProfileRequests(String body) throws Exception {
        JsonNode login = login(newTelegramId());

        assertProblem(mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(login))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 400, "MALFORMED_REQUEST");
        var stored = userService.getById(UUID.fromString(login.get("user").get("id").stringValue()));
        assertThat(stored.profileVersion()).isZero();
        assertThat(stored.profile().industry()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"expectedVersion\":null,\"industry\":\"Retail\"}",
            "{\"expectedVersion\":-1,\"industry\":\"Retail\"}", "{\"expectedVersion\":0}",
            "{\"expectedVersion\":0,\"industry\":\"  \"}",
            "{\"expectedVersion\":0,\"businessStage\":\"UNKNOWN\"}",
            "{\"expectedVersion\":0,\"taxRegime\":\"usn_6\"}",
            "{\"expectedVersion\":0,\"timeZone\":\"Mars/Olympus\"}",
            "{\"expectedVersion\":0,\"timeZone\":\"+03:00\"}"
    })
    void reportsSemanticFieldErrorsWithoutChangingProfile(String body) throws Exception {
        JsonNode login = login(newTelegramId());

        MvcResult response = mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(login))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertProblem(response, 422, "VALIDATION_FAILED");
        assertThat(json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("errors").isArray()).isTrue();
        assertThat(userService.getById(UUID.fromString(login.get("user").get("id").stringValue()))
                .profileVersion()).isZero();
    }

    @Test
    void measuresTextLengthInUnicodeCodePoints() throws Exception {
        JsonNode login = login(newTelegramId());
        String industry = "😀".repeat(100);
        assertThat(mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("expectedVersion", 0, "industry", industry))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertProblem(mvc.perform(patch("/api/v1/me/profile").header("Authorization", bearer(login))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                                Map.of("expectedVersion", 1, "industry", industry + "😀"))))
                .andReturn(), 422, "VALIDATION_FAILED");
    }

    @Test
    void rejectsMissingInvalidAndMultipleAuthorizationHeaders() throws Exception {
        assertProblem(mvc.perform(get("/api/v1/me")).andReturn(), 401, "AUTH_REQUIRED");
        for (String header : List.of("Basic anything", "Bearer invalid", "Bearer " + "a".repeat(43))) {
            assertProblem(mvc.perform(get("/api/v1/me").header("Authorization", header)).andReturn(),
                    401, "AUTH_REQUIRED");
        }
        JsonNode login = login(newTelegramId());
        assertProblem(mvc.perform(get("/api/v1/me").header("Authorization", bearer(login), bearer(login)))
                .andReturn(), 401, "AUTH_REQUIRED");
    }

    @Test
    void rejectsInvalidTelegramProofAndMalformedLoginBody() throws Exception {
        assertProblem(mvc.perform(post("/api/v1/auth/telegram").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"hash=bad\"}")).andReturn(), 401, "TELEGRAM_AUTH_INVALID");
        assertProblem(mvc.perform(post("/api/v1/auth/telegram").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":42}")).andReturn(), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(post("/api/v1/auth/telegram").contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"anything\",\"userId\":42}")).andReturn(), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(post("/api/v1/auth/telegram").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 422, "VALIDATION_FAILED");
    }

    private long newTelegramId() {
        long id = ThreadLocalRandom.current().nextLong(1L << 49, 1L << 50);
        testTelegramIds.add(id);
        return id;
    }

    private JsonNode login(long telegramId) throws Exception {
        String initData = TelegramInitDataValidatorTest.signedUser(telegramId, TelegramInitDataValidatorTest.NOW);
        String body = json.writeValueAsString(Map.of("initData", initData));
        MvcResult response = mvc.perform(post("/api/v1/auth/telegram").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        JsonNode login = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        testTokens.add(login.get("accessToken").stringValue());
        assertThat(response.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getResponse().getHeader("Set-Cookie")).isNull();
        return login;
    }

    private String bearer(JsonNode login) {
        return "Bearer " + login.get("accessToken").stringValue();
    }

    private void assertProblem(MvcResult response, int status, String code) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(status);
        assertThat(response.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(problem.get("code").stringValue()).isEqualTo(code);
        assertThat(problem.get("status").intValue()).isEqualTo(status);
        String requestId = problem.get("requestId").stringValue();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(response.getResponse().getHeader("X-Request-Id"));
        assertThat(problem.get("instance").stringValue()).startsWith("/api/v1/");
        assertThat(problem.get("type").stringValue()).isEqualTo("about:blank");
        assertThat(problem.get("title").stringValue()).isNotBlank();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return mock(Clock.class);
        }
    }
}

package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.integration.OllamaChatGateway;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.model.FormatRepairBudget;
import com.alfatgbotik.assistant.service.ProfileFactExtractor;
import com.alfatgbotik.identity.api.ProfileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileFactExtractorTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ProfileFactExtractor facts = new ProfileFactExtractor(null, json);

    ProfileFactExtractorTest() throws Exception { }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "У меня кофейня|industry|кофейня",
            "Я новичок|experience|BEGINNER",
            "Мой налоговый режим УСН 6%|taxRegime|USN_6",
            "Мой часовой пояс Europe/Moscow|timeZone|Europe/Moscow",
            "Мой бизнес уже работает|businessStage|OPERATING"
    })
    void acceptsOnlySupportedExplicitFacts(String message, String field, String value) {
        JsonNode result = facts.verifiedPatch(change(field, value, message), context(message));
        assertThat(result.get(field).stringValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Если бы у меня была кофейня|industry|кофейня",
            "У друга кофейня|industry|кофейня",
            "У меня нет кофейни|industry|кофейня",
            "У меня кофейня|experience|BEGINNER",
            "У меня кофейня|businessStage|OPERATING",
            "Живу в Москве|timeZone|Europe/Moscow",
            "Напомни завтра по Москве|timeZone|Europe/Moscow",
            "Стоит ли мне перейти на УСН 6%?|taxRegime|USN_6",
            "Хочу перейти на УСН 6%|taxRegime|USN_6",
            "Мой налоговый режим не УСН 6%|taxRegime|USN_6"
    })
    void dropsHypothesesOtherPeopleNegationAndGuessedValues(String message, String field, String value) {
        assertThat(facts.verifiedPatch(change(field, value, message), context(message)).isEmpty()).isTrue();
    }

    @Test
    void requiresEvidenceFromCurrentUserAndPreservesExplicitNull() {
        assertThat(facts.verifiedPatch(change("industry", "кофейня", "У меня кофейня"), context("Другое сообщение")).isEmpty()).isTrue();
        var known = new ProfileDto(null, "кофейня", null, null, null, null);
        var context = new AssistantContext("0.1.0", Instant.now(), "Удали сферу бизнеса из моего профиля", known,
                List.of(), List.of(), List.of(), null);
        var body = change("industry", null, context.message());
        var patch = facts.verifiedPatch(body, context);
        assertThat(patch.has("industry")).isTrue();
        assertThat(patch.get("industry").isNull()).isTrue();
    }

    @Test
    void boundsHistoryBeforeProfileExtractionAfterLongConversation() throws Exception {
        var gateway = new OllamaChatGateway(null, json, "test", "") {
            @Override
            public AssistantModel.ModelReply complete(String instructions, Object input, Map<?, ?> schema,
                                                       int tokens, ConsultationDeadline deadline) {
                JsonNode history = json.valueToTree(input).get("history");
                assertThat(history.size()).isEqualTo(2);
                for (JsonNode item : history) assertThat(item.get("text").stringValue()).hasSize(240);
                return new AssistantModel.ModelReply("{\"changes\":[]}", "test", "test");
            }
        };
        var context = context("У меня кофейня");
        var longHistory = java.util.stream.IntStream.range(0, 12).mapToObj(index ->
                new AssistantContext.HistoryMessage(index % 2 == 0 ? "USER" : "ASSISTANT", "т".repeat(3000))).toList();
        var withHistory = new AssistantContext(context.schemaVersion(), context.now(), context.message(), context.profile(),
                longHistory, List.of(), List.of(), null);
        assertThat(new ProfileFactExtractor(gateway, json).extract(withHistory, new FormatRepairBudget(),
                new ConsultationDeadline(Duration.ofSeconds(2))).patch().isEmpty()).isTrue();
    }

    private JsonNode change(String field, String value, String evidence) {
        var result = json.createObjectNode();
        var item = result.putArray("changes").addObject().put("field", field).put("evidence", evidence);
        item.set("value", json.valueToTree(value));
        return result;
    }

    private AssistantContext context(String message) {
        return new AssistantContext("0.1.0", Instant.now(), message, new ProfileDto(null, null, null, null, null, null),
                List.of(), List.of(), List.of(), null);
    }
}

package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.assistant.service.AssistantReplyFormatter;
import com.alfatgbotik.assistant.service.AssistantResponseValidator;
import com.alfatgbotik.identity.api.ProfileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantResponseValidatorTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AssistantResponseValidator validator = new AssistantResponseValidator(json);
    private final UUID chunk = UUID.randomUUID();
    private final UUID material = UUID.randomUUID();

    @Test
    void acceptsGroundedAnswerAndBuildsLinksFromServerCatalog() {
        var response = validator.validate(answer(), context("https://example.test/material"));
        String reply = new AssistantReplyFormatter().format(response, context("https://example.test/material"));
        assertThat(reply).contains("Ответ по источнику", "Server title", "https://example.test/material", "Бесплатно");
        assertThat(response.sourceChunkIds()).containsExactly(chunk);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "status", "answer", "sourceChunkIds", "recommendations", "profilePatch", "planProposal", "reminderProposal"})
    void requiresEveryContractField(String field) {
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(answer());
        body.remove(field);
        assertThatThrownBy(() -> validator.validate(body.toString(), context("https://example.test/material")))
                .isInstanceOf(AssistantFailure.class);
    }

    @Test
    void rejectsUnknownIdsExtraFieldsUrlsAndInconsistentStatus() {
        List<String> invalid = List.of(
                answer().replace(chunk.toString(), UUID.randomUUID().toString()),
                answer().replace(material.toString(), UUID.randomUUID().toString()),
                answer().replace("Ответ по источнику", "Открой https://invented.test"),
                answer().replace("\"ANSWER\"", "\"CLARIFY\""),
                answer().replace("\"0.1.0\"", "\"1.0.0\""),
                answer().replace("\"profilePatch\":{}", "\"profilePatch\":{\"unknown\":1}"),
                answer().replace("\"planProposal\":null", "\"planProposal\":{}"),
                answer().replace("\"reminderProposal\":null", "\"reminderProposal\":{}"),
                "```json\n" + answer() + "\n```",
                answer().replace("\"answer\":", "\"unexpected\":1,\"answer\":"));
        for (String text : invalid) {
            assertThatThrownBy(() -> validator.validate(text, context("https://example.test/material")))
                    .isInstanceOf(AssistantFailure.class);
        }
    }

    @Test
    void treatsLargeTelegramReplyAsRepairableFailureWithoutCuttingLinks() {
        var response = validator.validate(answer(), context("https://example.test/" + "a".repeat(4096)));
        assertThatThrownBy(() -> new AssistantReplyFormatter().format(response, context("https://example.test/" + "a".repeat(4096))))
                .isInstanceOfSatisfying(AssistantFailure.class, failure -> assertThat(failure.code()).isEqualTo("REPLY_TOO_LONG"));
    }

    private String answer() {
        return """
                {"schemaVersion":"0.1.0","status":"ANSWER","answer":"Ответ по источнику",
                "sourceChunkIds":["%s"],"recommendations":[{"materialId":"%s","reason":"Для вашей задачи"}],
                "profilePatch":{},"planProposal":null,"reminderProposal":null}
                """.formatted(chunk, material);
    }

    private AssistantContext context(String url) {
        return new AssistantContext("0.1.0", Instant.parse("2026-11-15T10:00:00Z"), "Вопрос",
                new ProfileDto(null, null, null, null, null, "Europe/Moscow"), List.of(),
                List.of(new AssistantContext.Source(chunk, material, "Server title", "Разрешённый текст источника")),
                List.of(new AssistantContext.Material(material, "Server title", "ARTICLE", null, false, null, url)), null);
    }
}

package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.assistant.service.ReminderInterpreter;
import com.alfatgbotik.identity.api.ProfileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderInterpreterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ReminderInterpreter parser = new ReminderInterpreter(json);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Напомни завтра в 9 по Москве продолжить курс|2026-11-16T06:00:00Z",
            "Напомни через 30 минут продолжить курс|2026-11-15T10:30:00Z",
            "Напомни через час продолжить курс|2026-11-15T11:00:00Z",
            "Напомни через 1 час 30 минут продолжить курс|2026-11-15T11:30:00Z",
            "Напомни через полчаса продолжить курс|2026-11-15T10:30:00Z",
            "Напомни 16.11.2026 в 18:00 продолжить курс|2026-11-16T18:00:00Z",
            "Напомни 2026-11-16 в 18:00 продолжить курс|2026-11-16T18:00:00Z",
            "Напомни 16 ноября в 6 вечера продолжить курс|2026-11-16T18:00:00Z"
    })
    void parsesFutureOnceRules(String message, String expected) {
        var result = parser.interpret(context(message, "UTC", List.of()), json.createObjectNode());
        assertThat(result.status()).isEqualTo(AssistantResponse.Status.ACTION_PROPOSAL);
        assertThat(result.reminderProposal().path("schedule").path("at").stringValue()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Напоминай ежедневно в 09:30|DAILY",
            "Напоминай каждый понедельник в 18:00|WEEKLY",
            "Напоминай ежемесячно 31-го числа в 09:00|MONTHLY",
            "Напоминай в последний день месяца в 09:00|MONTHLY"
    })
    void preservesLocalRecurringRules(String message, String type) {
        var result = parser.interpret(context(message, "Europe/Moscow", List.of()), json.createObjectNode());
        assertThat(result.status()).isEqualTo(AssistantResponse.Status.ACTION_PROPOSAL);
        var schedule = result.reminderProposal().get("schedule");
        assertThat(schedule.get("type").stringValue()).isEqualTo(type);
        assertThat(schedule.get("startDate").stringValue()).isEqualTo("2026-11-15");
        if (type.equals("MONTHLY")) assertThat(schedule.get("dayOfMonth").intValue()).isEqualTo(31);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Напомни завтра", "Напомни уплатить налог", "Напоминай по будням в 9",
            "Напомни вчера в 9", "Напомни завтра в 25:00", "Напомни 31.02.2027 в 9", "Напомни завтра в 9 или 10",
            "Напоминай по понедельникам и вторникам в 9", "Напомни через 0 минут", "Напомни завтра в 9 UTC+3:30"})
    void clarifiesMissingUnsupportedAndAmbiguousValues(String message) {
        var result = parser.interpret(context(message, "UTC", List.of()), json.createObjectNode());
        assertThat(result.status()).isEqualTo(AssistantResponse.Status.CLARIFY);
        assertThat(result.reminderProposal().isNull()).isTrue();
    }

    @Test
    void acceptsExplicitZoneWithoutChangingProfileAndDoesNotInferItFromResidence() {
        var emptyZone = context("Напомни завтра в 9 по Москве", null, List.of());
        assertThat(parser.interpret(emptyZone, json.createObjectNode()).status()).isEqualTo(AssistantResponse.Status.ACTION_PROPOSAL);
        assertThat(emptyZone.profile().timeZone()).isNull();
        assertThat(parser.interpret(context("Живу в Москве. Напомни завтра в 9", null, List.of()), json.createObjectNode()).status())
                .isEqualTo(AssistantResponse.Status.CLARIFY);
    }

    @Test
    void continuesSeveralClarificationsButDoesNotReuseOldIntentForNewTopic() {
        var history = List.of(new AssistantContext.HistoryMessage("USER", "Напомни продолжить курс"),
                new AssistantContext.HistoryMessage("ASSISTANT", "В каком часовом поясе?"),
                new AssistantContext.HistoryMessage("USER", "по Москве"),
                new AssistantContext.HistoryMessage("ASSISTANT", "Во сколько напомнить?"),
                new AssistantContext.HistoryMessage("USER", "9 утра"),
                new AssistantContext.HistoryMessage("ASSISTANT", "На какую дату поставить напоминание?"));
        var result = parser.interpret(context("завтра", null, history), json.createObjectNode());
        assertThat(result.status()).isEqualTo(AssistantResponse.Status.ACTION_PROPOSAL);
        assertThat(result.reminderProposal().path("schedule").path("at").stringValue()).isEqualTo("2026-11-16T06:00:00Z");
        assertThat(parser.applies(context("Как удерживать клиентов?", null, history))).isFalse();
        assertThat(parser.applies(context("Напомни что такое точка безубыточности", null, history))).isFalse();
    }

    private AssistantContext context(String message, String zone, List<AssistantContext.HistoryMessage> history) {
        return new AssistantContext("0.1.0", Instant.parse("2026-11-15T10:00:00Z"), message,
                new ProfileDto(null, null, null, null, null, zone), history, List.of(), List.of(), null);
    }
}

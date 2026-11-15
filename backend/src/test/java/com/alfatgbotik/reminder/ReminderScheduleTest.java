package com.alfatgbotik.reminder;

import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.reminder.api.ReminderRequests;
import com.alfatgbotik.reminder.model.ReminderSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderScheduleTest {

    @ParameterizedTest
    @CsvSource({
            "2027-01-31T06:00:00Z,2027-02-28T06:00:00Z",
            "2028-01-31T06:00:00Z,2028-02-29T06:00:00Z",
            "2028-02-29T06:00:00Z,2028-03-31T06:00:00Z",
            "2027-12-31T06:00:00Z,2028-01-31T06:00:00Z"
    })
    void retainsDay31AcrossShortMonthsAndYears(String after, String expected) {
        var schedule = new ReminderSchedule(ReminderSchedule.Type.MONTHLY, null, "Europe/Moscow",
                LocalTime.of(9, 0), LocalDate.of(2026, 1, 1), null, 31);
        assertThat(schedule.nextAfter(Instant.parse(after))).isEqualTo(Instant.parse(expected));
        assertThat(schedule.dayOfMonth()).isEqualTo(31);
    }

    @Test
    void shiftsMissingDstTimeAndChoosesFirstRepeatedTime() {
        var schedule = daily("Europe/Berlin", "02:30", "2026-01-01");
        assertThat(schedule.nextAfter(Instant.parse("2026-03-28T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
        assertThat(schedule.nextAfter(Instant.parse("2026-10-24T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(schedule.nextAfter(Instant.parse("2026-10-25T00:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-26T01:30:00Z"));
    }

    @Test
    void respectsStartDateAndUsesMostRecentMissedOccurrence() {
        var schedule = daily("Europe/Moscow", "09:00", "2026-11-20");
        assertThat(schedule.nextAfter(Instant.parse("2026-11-15T10:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-20T06:00:00Z"));
        assertThat(schedule.latestAtOrBefore(Instant.parse("2026-11-19T10:00:00Z"))).isNull();
        assertThat(schedule.latestAtOrBefore(Instant.parse("2026-12-01T07:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-01T06:00:00Z"));
        var weekly = new ReminderSchedule(ReminderSchedule.Type.WEEKLY, null, "UTC", LocalTime.NOON,
                LocalDate.of(2026, 11, 15), DayOfWeek.MONDAY, null);
        assertThat(weekly.nextAfter(Instant.parse("2026-11-15T13:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-16T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"title\":\"test\",\"schedule\":null,\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"ONCE\",\"at\":\"2027-01-01T00:00:00+00:00\",\"timeZone\":\"UTC\"},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"ONCE\",\"at\":\"2027-01-01T00:00:00Z\",\"timeZone\":\"UTC\",\"localTime\":null},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":1,\"schedule\":{},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"DAILY\",\"localTime\":\"9:00\",\"startDate\":\"2027-01-01\",\"timeZone\":\"UTC\"},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"MONTHLY\",\"localTime\":\"09:00\",\"startDate\":\"2027-01-01\",\"timeZone\":\"UTC\",\"dayOfMonth\":\"31\"},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"ONCE\",\"at\":\"2027-01-01T00:00:00Z\",\"timeZone\":\"Mars/Test\"},\"materialId\":null,\"planStepId\":null}",
            "{\"title\":\"test\",\"schedule\":{\"type\":\"ONCE\",\"at\":\"2027-01-01T00:00:00Z\",\"timeZone\":\"UTC\"},\"planStepId\":null}"
    })
    void rejectsMalformedAndIncompleteCommands(String body) {
        assertThatThrownBy(() -> ReminderRequests.create(JsonMapper.builder().build().readTree(body)))
                .isInstanceOf(RequestValidationException.class);
    }

    private ReminderSchedule daily(String zone, String time, String date) {
        return new ReminderSchedule(ReminderSchedule.Type.DAILY, null, zone, LocalTime.parse(time),
                LocalDate.parse(date), null, null);
    }
}

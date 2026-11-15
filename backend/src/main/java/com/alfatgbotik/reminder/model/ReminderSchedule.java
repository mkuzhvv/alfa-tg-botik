package com.alfatgbotik.reminder.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.alfatgbotik.common.error.RequestValidationException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReminderSchedule(Type type, Instant at, String timeZone, @JsonFormat(pattern = "HH:mm") LocalTime localTime,
                               LocalDate startDate, DayOfWeek dayOfWeek, Integer dayOfMonth) {

    public enum Type { ONCE, DAILY, WEEKLY, MONTHLY }

    public ReminderSchedule {
        if (type == null || timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw RequestValidationException.invalid("schedule", "Expected a type and an IANA time zone");
        }
        boolean once = type == Type.ONCE;
        if ((once && (at == null || localTime != null || startDate != null))
                || (!once && (at != null || localTime == null || startDate == null))
                || (localTime != null && (localTime.getSecond() != 0 || localTime.getNano() != 0))
                || (type == Type.WEEKLY ? dayOfWeek == null : dayOfWeek != null)
                || (type == Type.MONTHLY ? dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31 : dayOfMonth != null)) {
            throw RequestValidationException.invalid("schedule", "Fields do not match the schedule type");
        }
    }

    public Instant nextAfter(Instant after) {
        if (type == Type.ONCE) {
            return at.isAfter(after) ? at : null;
        }
        LocalDate date = after.atZone(ZoneId.of(timeZone)).toLocalDate();
        if (date.isBefore(startDate)) {
            date = startDate;
        }
        date = switch (type) {
            case WEEKLY -> date.with(TemporalAdjusters.nextOrSame(dayOfWeek));
            case MONTHLY -> monthly(date);
            default -> date;
        };
        while (date.isBefore(startDate) || !instant(date).isAfter(after)) {
            date = shift(date, 1);
        }
        return instant(date);
    }

    public Instant latestAtOrBefore(Instant now) {
        if (type == Type.ONCE) {
            return at.isAfter(now) ? null : at;
        }
        LocalDate date = now.atZone(ZoneId.of(timeZone)).toLocalDate();
        date = switch (type) {
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(dayOfWeek));
            case MONTHLY -> monthly(date);
            default -> date;
        };
        while (instant(date).isAfter(now)) {
            date = shift(date, -1);
        }
        return date.isBefore(startDate) ? null : instant(date);
    }

    public Instant requireFuture(Instant now) {
        Instant next = nextAfter(now);
        if (next == null) {
            throw RequestValidationException.invalid("schedule.at", "ONCE.at must be in the future");
        }
        return next;
    }

    private Instant instant(LocalDate date) {
        return date.atTime(localTime).atZone(ZoneId.of(timeZone)).toInstant();
    }

    private LocalDate monthly(LocalDate date) {
        return date.withDayOfMonth(Math.min(dayOfMonth, date.lengthOfMonth()));
    }

    private LocalDate shift(LocalDate date, int direction) {
        return switch (type) {
            case DAILY -> date.plusDays(direction);
            case WEEKLY -> date.plusWeeks(direction);
            case MONTHLY -> monthly(date.withDayOfMonth(1).plusMonths(direction));
            default -> throw new IllegalStateException("ONCE has no recurrence");
        };
    }
}

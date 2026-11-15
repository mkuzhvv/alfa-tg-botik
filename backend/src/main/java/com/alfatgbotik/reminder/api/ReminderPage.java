package com.alfatgbotik.reminder.api;

import java.util.List;

public record ReminderPage(List<ReminderDto> items, long total, int offset, int limit) {
}

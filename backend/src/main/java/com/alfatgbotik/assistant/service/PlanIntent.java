package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.AssistantContext;

import java.util.regex.Pattern;

public final class PlanIntent {

    private static final Pattern REQUEST = Pattern.compile("(?isuU)(?:^|[.!?,]\\s*)(?:составь|создай|подготовь|предложи|разработай|сделай)\\s+(?:мне\\s+)?(?:новый\\s+)?план(?:\\s+(?:действий|обучения))?(?:\\s+(?:по|для|на|чтобы))?\\s*[:—-]?\\s*(.*)");

    private PlanIntent() {
    }

    public static boolean requested(AssistantContext context) {
        if (REQUEST.matcher(context.message()).find()) {
            return true;
        }
        var history = context.history();
        return !history.isEmpty() && context.message().length() < 250
                && history.getLast().role().equals("ASSISTANT")
                && history.getLast().text().matches("(?isuU).*для плана.*цель.*")
                && history.stream().filter(item -> item.role().equals("USER")).anyMatch(item -> REQUEST.matcher(item.text()).find());
    }

    public static String goal(AssistantContext context) {
        var match = REQUEST.matcher(context.message());
        String goal = match.find() ? match.group(1) : context.message();
        goal = goal.replaceFirst("(?iuU)^(?:(?:из|на|в)\\s+)?(?:[3-5]|три|трех|четыре|четырех|пять|пяти)\\s+шаг\\p{L}*\\s*(?:(?:по|для|чтобы)\\s+)?", "").strip();
        if (goal.isBlank()) {
            goal = context.profile().currentTask();
        }
        return goal != null && !goal.isBlank() && goal.codePointCount(0, goal.length()) <= 250 ? goal.strip() : null;
    }
}

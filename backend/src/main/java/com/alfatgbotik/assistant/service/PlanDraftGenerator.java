package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.OllamaChatGateway;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.model.FormatRepairBudget;
import com.alfatgbotik.common.api.JsonRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlanDraftGenerator {

    private final OllamaChatGateway model;
    private final JsonMapper json;
    private final String instructions;

    public PlanDraftGenerator(OllamaChatGateway model, JsonMapper json) throws IOException {
        this.model = model;
        this.json = json;
        instructions = new ClassPathResource("assistant/prompts/plan-v1.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    public Draft generate(AssistantContext context, String goal, FormatRepairBudget repair, ConsultationDeadline deadline) {
        Map<String, Evidence> passages = passages(context);
        int required = requiredSteps(context.message());
        if (passages.size() < required || context.catalog().isEmpty()) {
            return new Draft(ActionResponses.notFound(json), "rules:actions-1", "actions-1");
        }
        var input = json.createObjectNode().put("goal", goal).put("requiredSteps", required);
        input.set("profile", json.valueToTree(context.profile()));
        input.set("currentPlan", json.valueToTree(context.currentPlan()));
        input.set("catalog", json.valueToTree(context.catalog()));
        input.set("passages", json.valueToTree(passages.entrySet().stream().map(item -> Map.of("id", item.getKey(), "text", item.getValue().text())).toList()));
        Map<?, ?> schema = json.readValue("""
                {"type":"object","additionalProperties":false,"required":["status","clarification","steps"],"properties":{
                "status":{"enum":["ANSWER","CLARIFY","NOT_FOUND"]},"clarification":{"type":"string","maxLength":300},
                "steps":{"type":"array","maxItems":5,"items":{"type":"object","additionalProperties":false,"required":["title","evidenceId"],
                "properties":{"title":{"type":"string","minLength":1,"maxLength":160},"evidenceId":{"enum":%s}}}}}}
                """.formatted(json.writeValueAsString(passages.keySet())), Map.class);
        boolean corrected = false;
        while (true) {
            var generated = model.complete(instructions + (corrected ? "\nИсправь формат, число шагов и evidenceId. Используй только доступные ID." : ""),
                    input, schema, 800, deadline);
            try {
                var body = json.readTree(generated.text());
                JsonRequest request = new JsonRequest(body, Set.of("status", "clarification", "steps"));
                String status = request.requiredText("status", 20);
                String clarification = body.path("clarification").isString() ? body.get("clarification").stringValue() : null;
                if (clarification == null || !body.has("steps") || !body.get("steps").isArray()) throw invalid();
                if (status.equals("CLARIFY") && body.get("steps").isEmpty() && !clarification.isBlank()) {
                    return new Draft(ActionResponses.clarify(json, clarification, json.createObjectNode()), generated.modelName(), generated.modelRevision());
                }
                if (status.equals("NOT_FOUND") && body.get("steps").isEmpty()) {
                    return new Draft(ActionResponses.notFound(json), generated.modelName(), generated.modelRevision());
                }
                if (!status.equals("ANSWER") || !clarification.isEmpty() || body.get("steps").size() != required) throw invalid();
                Set<String> seen = new HashSet<>();
                LinkedHashSet<java.util.UUID> sourceIds = new LinkedHashSet<>();
                LinkedHashMap<java.util.UUID, String> reasons = new LinkedHashMap<>();
                var proposal = json.createObjectNode().put("goal", goal);
                var steps = proposal.putArray("steps");
                for (var value : body.get("steps")) {
                    JsonRequest item = new JsonRequest(value, Set.of("title", "evidenceId"));
                    String title = item.requiredText("title", 160);
                    String id = item.requiredText("evidenceId", 20);
                    Evidence evidence = passages.get(id);
                    if (evidence == null || !seen.add(id) || title.matches("(?is).*https?://.*|.*```.*")) throw invalid();
                    var step = steps.addObject().put("title", title).put("description", evidence.text());
                    step.set("materialIds", json.valueToTree(List.of(evidence.source().materialId())));
                    sourceIds.add(evidence.source().chunkId());
                    reasons.putIfAbsent(evidence.source().materialId(), "Материал для шага «" + title + "».");
                }
                List<AssistantResponse.Recommendation> recommendations = reasons.entrySet().stream().limit(3)
                        .map(item -> new AssistantResponse.Recommendation(item.getKey(), item.getValue())).toList();
                return new Draft(new AssistantResponse("0.1.0", AssistantResponse.Status.ANSWER,
                        "Предлагаю план из " + required + " шагов. Он будет сохранён после подтверждения.",
                        List.copyOf(sourceIds), recommendations, json.createObjectNode(), proposal, json.valueToTree(null)),
                        generated.modelName(), generated.modelRevision());
            } catch (RuntimeException exception) {
                if (!repair.take()) throw invalid();
                corrected = true;
            }
        }
    }

    private Map<String, Evidence> passages(AssistantContext context) {
        Map<String, Evidence> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (var source : context.sources()) {
            if (context.catalog().stream().noneMatch(item -> item.id().equals(source.materialId()))) continue;
            for (String paragraph : source.text().split("\\n\\s*\\n")) {
                if (paragraph.matches("(?is).*(в этой статье|в этом уроке|в этом выпуске|вы узнаете|разбираемся в).*")) continue;
                int start = 0;
                while (start < paragraph.length()) {
                    int end = Math.min(paragraph.length(), start + 350);
                    if (end < paragraph.length()) {
                        int space = paragraph.lastIndexOf(' ', end);
                        if (space >= start + 120) end = space;
                        if (Character.isLowSurrogate(paragraph.charAt(end))) end--;
                    }
                    String text = paragraph.substring(start, end).strip();
                    if (text.length() >= 35 && !text.matches("(?is).*https?://.*") && seen.add(text)) {
                        result.put("E" + (result.size() + 1), new Evidence(source, text));
                    }
                    start = end;
                }
            }
        }
        return result;
    }

    private int requiredSteps(String message) {
        var match = java.util.regex.Pattern.compile("(?iuU)\\b([3-5]|три|трех|четыре|четырех|пять|пяти)\\s+шаг").matcher(message);
        if (!match.find()) return 3;
        return switch (match.group(1).toLowerCase(java.util.Locale.ROOT)) {
            case "4", "четыре", "четырех" -> 4;
            case "5", "пять", "пяти" -> 5;
            default -> 3;
        };
    }

    private AssistantFailure invalid() { return new AssistantFailure("INVALID_PLAN_RESPONSE"); }
    private record Evidence(AssistantContext.Source source, String text) { }
    public record Draft(AssistantResponse response, String modelName, String modelRevision) { }
}

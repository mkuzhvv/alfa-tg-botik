package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.integration.OllamaChatGateway;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.model.FormatRepairBudget;
import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.identity.api.ProfileUpdateRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ProfileFactExtractor {

    private static final Set<String> FIELDS = Set.of("industry", "currentTask", "timeZone", "businessStage", "experience", "taxRegime");
    private final OllamaChatGateway model;
    private final JsonMapper json;
    private final String instructions;
    private final Map<?, ?> schema;

    public ProfileFactExtractor(OllamaChatGateway model, JsonMapper json) throws IOException {
        this.model = model;
        this.json = json;
        instructions = new ClassPathResource("assistant/prompts/profile-facts-v1.txt").getContentAsString(StandardCharsets.UTF_8);
        schema = json.readValue("""
                {"type":"object","additionalProperties":false,"required":["changes"],"properties":{"changes":{
                "type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,
                "required":["field","value","evidence"],"properties":{"field":{"enum":["industry","currentTask","timeZone","businessStage","experience","taxRegime"]},
                "value":{"type":["string","null"],"maxLength":500},"evidence":{"type":"string","minLength":1,"maxLength":400}}}}}}
                """, Map.class);
    }

    public Extraction extract(AssistantContext context, FormatRepairBudget repair, ConsultationDeadline deadline) {
        if (!needed(context)) {
            return new Extraction(json.createObjectNode(), null);
        }
        boolean corrected = false;
        var history = context.history().stream().skip(Math.max(0, context.history().size() - 2))
                .map(item -> new AssistantContext.HistoryMessage(item.role(), item.text().substring(0,
                        item.text().offsetByCodePoints(0, Math.min(240, item.text().codePointCount(0, item.text().length())))))).toList();
        while (true) {
            var reply = model.complete(instructions + (corrected ? "\nИсправь формат: верни только changes по схеме." : ""),
                    Map.of("message", context.message(), "profile", context.profile(), "history", history), schema, 800, deadline);
            try {
                return new Extraction(verifiedPatch(json.readTree(reply.text()), context), reply);
            } catch (Exception exception) {
                if (!repair.take()) {
                    throw new AssistantFailure("INVALID_PROFILE_EXTRACTION");
                }
                corrected = true;
            }
        }
    }

    public ObjectNode verifiedPatch(JsonNode body, AssistantContext context) {
        new JsonRequest(body, Set.of("changes"));
        JsonNode changes = body.get("changes");
        if (changes == null || !changes.isArray() || changes.size() > 6) {
            throw new AssistantFailure("INVALID_PROFILE_EXTRACTION");
        }
        ObjectNode result = json.createObjectNode();
        Set<String> conflicting = new HashSet<>();
        JsonNode known = json.valueToTree(context.profile());
        for (JsonNode change : changes) {
            JsonRequest item = new JsonRequest(change, Set.of("field", "value", "evidence"));
            String field = item.requiredText("field", 30);
            String evidence = item.requiredText("evidence", 400);
            if (!FIELDS.contains(field) || !change.has("value")) {
                throw new AssistantFailure("INVALID_PROFILE_EXTRACTION");
            }
            JsonNode value = change.get("value");
            if (!value.isNull() && !value.isString()) {
                throw new AssistantFailure("INVALID_PROFILE_EXTRACTION");
            }
            if (!supported(field, value, evidence, context)) {
                continue;
            }
            ObjectNode validation = json.createObjectNode().put("expectedVersion", 0);
            validation.set(field, value);
            try {
                ProfileUpdateRequest.toCommand(validation);
            } catch (RuntimeException exception) {
                continue;
            }
            if (result.has(field) && !result.get(field).equals(value)) {
                conflicting.add(field);
            }
            if (!value.equals(known.get(field))) {
                result.set(field, value);
            }
        }
        conflicting.forEach(result::remove);
        return result;
    }

    private boolean needed(AssistantContext context) {
        String text = normal(context.message());
        if (has(text, "у меня|\\bя\\b|\\bмо(?:й|я|ю|е|его|их|и)\\b|профил|хочу|планирую|открываю|запускаю|занимаюсь|владею|работаю на|удали|очисти|сбрось|забудь|убери")) {
            return !ReminderInterpreter.requested(text) || has(text, "профил|мой часов|у меня|\\bя\\b");
        }
        return !context.history().isEmpty() && context.message().length() < 200
                && has(normal(context.history().getLast().text()), "сфер|отрасл|ваш.*опыт|стади|ваш.*пояс|налогов.*режим");
    }

    private boolean supported(String field, JsonNode value, String evidence, AssistantContext context) {
        String message = context.message();
        int position = message.indexOf(evidence);
        if (position < 0 || evidence.isBlank()) {
            return false;
        }
        int start = Math.max(Math.max(message.lastIndexOf('.', position), message.lastIndexOf('?', position)), message.lastIndexOf('\n', position)) + 1;
        int end = message.length();
        for (String separator : new String[]{".", "?", "!", "\n"}) {
            int next = message.indexOf(separator, position + evidence.length());
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        String sentence = normal(message.substring(start, end));
        String quote = normal(evidence);
        String whole = normal(message);
        if (has(sentence, "\\b(если|допустим|представь|например|возможно|наверное|друг[ауе]?|другом|коллег\\p{L}*|знаком\\p{L}*|сосед\\p{L}*|брат\\p{L}*|сестр\\p{L}*|клиент\\p{L}*|партнер\\p{L}*)\\b")) {
            return false;
        }
        if (value.isNull()) {
            String words = switch (field) {
                case "industry" -> "сфер|отрасл|бизнес";
                case "currentTask" -> "задач|цел[ьи]";
                case "timeZone" -> "пояс|зон";
                case "experience" -> "опыт|уровень";
                case "businessStage" -> "стади|этап";
                default -> "налог|режим|усн|патент|нпд|осно|аусн";
            };
            return has(whole, "удали|очисти|сбрось|забудь|убери") && has(whole, words)
                    && !has(whole, "\\bне\\s+(удали|очисти|сбрось|забудь|убери)")
                    && !(field.equals("currentTask") && has(whole, "план") && !has(whole, "профил"));
        }
        String target = value.stringValue();
        if (has(target, "https?://|```|\\bselect\\b.*\\bfrom\\b|drop table")) {
            return false;
        }
        int current = Math.max(whole.lastIndexOf("теперь"), whole.lastIndexOf("сейчас"));
        if ((current >= 0 && !whole.substring(current).contains(quote)) || has(quote, "раньше|в прошлом|была|было|были")) {
            return false;
        }
        String prior = context.history().isEmpty() ? "" : normal(context.history().getLast().text());
        return switch (field) {
            case "industry" -> sameWords(target, quote) && !has(sentence, "\\bне\\s+(владею|занимаюсь|открываю|запускаю|" + Pattern.quote(normal(target)) + ")")
                    && (has(sentence, "у меня|мой бизнес|моя сфер|моя отрасл|владею|занимаюсь|открываю|запускаю|хочу открыть|планирую открыть|профил") || has(prior, "сфер|отрасл|какой.*бизнес"));
            case "currentTask" -> quote.contains(normal(target)) && !has(sentence, "\\bне\\s+(хочу|планирую|нужно)")
                    && (!has(sentence, "напомни") || has(sentence, "профил|моя задача|моя цель"))
                    && (has(sentence, "хочу|задач|цел[ьи]|планирую|нужно") || has(prior, "задач|цел[ьи]"));
            case "businessStage" -> enumProof(target, quote, Map.of("IDEA", "иде[яию]|планирую открыть", "LAUNCH", "открываю|запускаю|стадия запуска",
                    "OPERATING", "действующ|уже работает|бизнес работает", "GROWTH", "стади\\p{L}* (рост|развит)|масштабирую")) && !has(quote, "\\bне\\b");
            case "experience" -> enumProof(target, quote, Map.of("BEGINNER", "новичок|начинающ|неопытн|нет опыта|без опыта",
                    "EXPERIENCED", "\\bопытный\\b|\\bопытная\\b|много опыта|опыт есть"))
                    && (!target.equals("EXPERIENCED") || !has(quote, "нет опыта|без опыта|\\bне опыт"));
            case "taxRegime" -> enumProof(target, quote, Map.of("NPD", "\\bнпд\\b|самозанят", "USN_6", "\\bусн\\s*6(?!\\d)", "USN_15", "\\bусн\\s*15(?!\\d)",
                    "PATENT", "патент|\\bпсн\\b", "OSNO", "\\bосно\\b", "AUSN", "\\bаусн\\b")) && !has(sentence, "(?:хочу|планирую|собираюсь)\\s+перейти|перейти ли|выбрать ли|что такое|стоит ли|\\bне\\s+(?:на\\s+)?(?:усн|аусн|нпд|осно|самозанят|патент)");
            case "timeZone" -> (has(sentence, "мой часов\\p{L}* пояс|моя зона|в профиле|профиль") || has(prior, "ваш.*пояс|пояс.*профил"))
                    && target.equals(ReminderInterpreter.explicitZone(evidence));
            default -> false;
        };
    }

    private boolean sameWords(String value, String evidence) {
        if (evidence.contains(normal(value))) {
            return !has(evidence, "\\b(?:не|нет)\\s+" + Pattern.quote(normal(value)));
        }
        String[] target = normal(value).split("\\s+");
        String[] words = evidence.split("[^\\p{L}\\p{N}]+");
        for (int i = 0; i + target.length <= words.length; i++) {
            boolean matches = true;
            for (int j = 0; j < target.length; j++) {
                matches &= stem(target[j]).equals(stem(words[i + j]));
            }
            if (matches && (i == 0 || !words[i - 1].matches("не|нет"))) {
                return true;
            }
        }
        return false;
    }

    private String stem(String word) {
        return word.replaceFirst("(?:ями|ами|ого|ему|ыми|ими|ов|ев|ий|ый|ая|яя|ое|ее|ые|ие|ом|ем|ой|ей|ую|юю|ым|им|ам|ям|ах|ях|а|я|ы|и|у|ю|е)$", "");
    }

    private boolean enumProof(String value, String text, Map<String, String> patterns) {
        return patterns.containsKey(value) && has(text, patterns.get(value));
    }

    private String normal(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
    }

    private boolean has(String value, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS).matcher(value).find();
    }

    public record Extraction(ObjectNode patch, AssistantModel.ModelReply model) {
    }
}

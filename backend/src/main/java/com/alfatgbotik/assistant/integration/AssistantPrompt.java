package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.model.AssistantContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AssistantPrompt {

    public static final String VERSION = "consultation-v2-actions-1";
    public static final int CONTEXT_TOKENS = 16384;
    public static final int OUTPUT_TOKENS = 1200;
    private final JsonMapper json;
    private final String instructions;
    private final String schemaText;
    private final Map<?, ?> schema;

    public AssistantPrompt(JsonMapper json) throws IOException {
        this.json = json;
        instructions = new ClassPathResource("assistant/prompts/consultation-v2.txt").getContentAsString(StandardCharsets.UTF_8);
        var full = (tools.jackson.databind.node.ObjectNode) json.readTree(
                new ClassPathResource("assistant/response-schema.json").getContentAsString(StandardCharsets.UTF_8));
        var properties = (tools.jackson.databind.node.ObjectNode) full.get("properties");
        ((tools.jackson.databind.node.ObjectNode) properties.get("status")).set("enum", json.valueToTree(java.util.List.of("ANSWER", "CLARIFY", "NOT_FOUND")));
        properties.set("profilePatch", json.readTree("{\"type\":\"object\",\"additionalProperties\":false}"));
        properties.set("planProposal", json.readTree("{\"type\":\"null\"}"));
        properties.set("reminderProposal", json.readTree("{\"type\":\"null\"}"));
        full.remove("$defs");
        schemaText = json.writeValueAsString(full);
        schema = json.readValue(schemaText, Map.class);
    }

    public String instructions(String correction) {
        return instructions + (correction == null ? "" : "\nПредыдущая попытка отклонена: " + correction
                + ". Верни исправленный JSON, используй только ID из входа. При слишком длинном ответе сократи текст и рекомендации.");
    }

    public String input(AssistantContext context) {
        return json.writeValueAsString(context);
    }

    public Map<?, ?> schema() {
        return schema;
    }

    public boolean fits(AssistantContext context, String correction) {
        int bytes = (instructions(correction) + input(context) + schemaText).getBytes(StandardCharsets.UTF_8).length;
        return bytes <= CONTEXT_TOKENS - OUTPUT_TOKENS - 1024;
    }
}

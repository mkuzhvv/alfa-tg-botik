package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.model.AssistantContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class AssistantPrompt {

    public static final String VERSION = "consultation-v3-bounded-sources";
    public static final int CONTEXT_TOKENS = 16384;
    public static final int OUTPUT_TOKENS = 1200;
    private final JsonMapper json;
    private final String instructions;
    private final String schemaText;

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
    }

    public String instructions(String correction) {
        return instructions + (correction == null ? "" : "\nПредыдущая попытка отклонена: " + correction
                + ". Верни исправленный JSON, используй только ID из входа. При слишком длинном ответе сократи текст и рекомендации.");
    }

    public String input(AssistantContext context) {
        return json.writeValueAsString(context);
    }

    public Map<?, ?> schema(AssistantContext context) {
        ObjectNode constrained = (ObjectNode) json.readTree(schemaText);
        var properties = constrained.get("properties");
        var sourceIds = context.sources().stream().map(source -> source.chunkId().toString()).distinct().toList();
        var sources = (ObjectNode) properties.get("sourceChunkIds");
        if (sourceIds.isEmpty()) {
            sources.put("maxItems", 0);
        } else {
            ((ObjectNode) sources.get("items")).set("enum", json.valueToTree(sourceIds));
        }
        var materialIds = context.catalog().stream().map(material -> material.id().toString()).distinct().toList();
        var recommendations = (ObjectNode) properties.get("recommendations");
        recommendations.put("maxItems", Math.min(3, materialIds.size()));
        if (!materialIds.isEmpty()) {
            ((ObjectNode) recommendations.get("items").get("properties").get("materialId"))
                    .set("enum", json.valueToTree(materialIds));
        }
        return json.readValue(json.writeValueAsString(constrained), Map.class);
    }

    public boolean fits(AssistantContext context, String correction) {
        int bytes = (instructions(correction) + input(context) + json.writeValueAsString(schema(context)))
                .getBytes(StandardCharsets.UTF_8).length;
        return bytes <= CONTEXT_TOKENS - OUTPUT_TOKENS - 1024;
    }
}

package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.AssistantResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

public final class ActionResponses {

    private ActionResponses() {
    }

    public static AssistantResponse clarify(JsonMapper json, String question, JsonNode patch) {
        return new AssistantResponse("0.1.0", AssistantResponse.Status.CLARIFY, question, List.of(), List.of(),
                patch, json.valueToTree(null), json.valueToTree(null));
    }

    public static AssistantResponse notFound(JsonMapper json) {
        return new AssistantResponse("0.1.0", AssistantResponse.Status.NOT_FOUND,
                "В доступных материалах не нашлось достаточно источников для такого плана.", List.of(), List.of(),
                json.createObjectNode(), json.valueToTree(null), json.valueToTree(null));
    }
}

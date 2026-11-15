package com.alfatgbotik.assistant.model;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record AssistantResponse(String schemaVersion, Status status, String answer, List<UUID> sourceChunkIds,
                                List<Recommendation> recommendations, JsonNode profilePatch,
                                JsonNode planProposal, JsonNode reminderProposal) {

    public enum Status { ANSWER, CLARIFY, NOT_FOUND, ACTION_PROPOSAL }

    public record Recommendation(UUID materialId, String reason) {
    }
}

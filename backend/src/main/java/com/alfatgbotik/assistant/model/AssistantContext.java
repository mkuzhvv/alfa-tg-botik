package com.alfatgbotik.assistant.model;

import com.alfatgbotik.identity.api.ProfileDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssistantContext(String schemaVersion, Instant now, String message, ProfileDto profile,
                               List<HistoryMessage> history, List<Source> sources,
                               List<Material> catalog, CurrentPlan currentPlan) {

    public record HistoryMessage(String role, String text) {
    }

    public record Source(UUID chunkId, UUID materialId, String title, String text) {
    }

    public record Material(UUID id, String title, String kind, String description,
                           boolean isPaid, String duration, String url) {
    }

    public record CurrentPlan(UUID id, String goal, String status, long version, List<Step> steps,
                              Instant createdAt, Instant updatedAt) {
    }

    public record Step(UUID id, int position, String title, String description, String status, List<Material> materials) {
    }
}

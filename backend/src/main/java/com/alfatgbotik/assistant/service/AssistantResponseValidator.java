package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.identity.api.ProfileUpdateRequest;
import com.alfatgbotik.reminder.api.ReminderRequests;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class AssistantResponseValidator {

    private static final Set<String> FIELDS = Set.of("schemaVersion", "status", "answer", "sourceChunkIds",
            "recommendations", "profilePatch", "planProposal", "reminderProposal");
    private final JsonMapper json;

    public AssistantResponseValidator(JsonMapper json) {
        this.json = json;
    }

    public AssistantResponse validate(String text, AssistantContext context) {
        try {
            if (text == null || text.length() > 24000) {
                throw invalid();
            }
            JsonNode body = json.readTree(text);
            JsonRequest request = new JsonRequest(body, FIELDS);
            if (body.size() != FIELDS.size() || !FIELDS.stream().allMatch(body::has)) {
                throw invalid();
            }
            if (!request.requiredText("schemaVersion", 20).equals("0.1.0")) {
                throw invalid();
            }
            request.requiredText("status", 30);
            AssistantResponse.Status status = request.nullableEnum("status", AssistantResponse.Status.class);
            String answer = request.requiredText("answer", 2500);
            if (answer.matches("(?is).*\\b(?:https?://|www\\.).*")) {
                throw invalid();
            }
            List<UUID> sources = ids(body.get("sourceChunkIds"), 8);
            Set<UUID> sourceIds = new HashSet<>(context.sources().stream().map(AssistantContext.Source::chunkId).toList());
            Set<UUID> materialIds = new HashSet<>(context.catalog().stream().map(AssistantContext.Material::id).toList());
            if (!sourceIds.containsAll(sources)) {
                throw invalid();
            }
            JsonNode recommendations = array(body.get("recommendations"), 3);
            List<AssistantResponse.Recommendation> result = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            for (JsonNode recommendation : recommendations) {
                JsonRequest item = new JsonRequest(recommendation, Set.of("materialId", "reason"));
                UUID id = ReminderRequests.uuid(item.requiredText("materialId", 36), "materialId");
                if (!materialIds.contains(id) || !seen.add(id)) {
                    throw invalid();
                }
                result.add(new AssistantResponse.Recommendation(id, item.requiredText("reason", 300)));
            }
            JsonNode patch = body.get("profilePatch");
            if (patch == null || !patch.isObject()) {
                throw invalid();
            }
            if (!patch.isEmpty()) {
                var withVersion = patch.deepCopy();
                if (withVersion.has("expectedVersion")) {
                    throw invalid();
                }
                ((tools.jackson.databind.node.ObjectNode) withVersion).put("expectedVersion", 0);
                ProfileUpdateRequest.toCommand(withVersion);
            }
            JsonNode plan = body.get("planProposal");
            JsonNode reminder = body.get("reminderProposal");
            if (plan == null || reminder == null) {
                throw invalid();
            }
            switch (status) {
                case ANSWER -> {
                    if (sources.isEmpty() || !reminder.isNull()) {
                        throw invalid();
                    }
                }
                case CLARIFY, NOT_FOUND -> {
                    if (!sources.isEmpty() || !plan.isNull() || !reminder.isNull()
                            || (status == AssistantResponse.Status.CLARIFY && !result.isEmpty())) {
                        throw invalid();
                    }
                }
                case ACTION_PROPOSAL -> {
                    if (reminder.isNull() || !plan.isNull()) {
                        throw invalid();
                    }
                }
            }
            if (!plan.isNull()) {
                validatePlan(plan, materialIds);
            }
            if (!reminder.isNull()) {
                validateReminder(reminder, context, materialIds);
            }
            return new AssistantResponse("0.1.0", status, answer, sources, List.copyOf(result), patch, plan, reminder);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private void validatePlan(JsonNode plan, Set<UUID> allowed) {
        JsonRequest request = new JsonRequest(plan, Set.of("goal", "steps"));
        request.requiredText("goal", 250);
        JsonNode steps = array(plan.get("steps"), 5);
        if (steps.size() < 3) {
            throw invalid();
        }
        for (JsonNode step : steps) {
            JsonRequest item = new JsonRequest(step, Set.of("title", "description", "materialIds"));
            item.requiredText("title", 160);
            item.requiredText("description", 600);
            List<UUID> ids = ids(step.get("materialIds"), 2);
            if (ids.isEmpty() || !allowed.containsAll(ids)) {
                throw invalid();
            }
        }
    }

    private void validateReminder(JsonNode node, AssistantContext context, Set<UUID> materials) {
        var command = ReminderRequests.create(node);
        command.schedule().requireFuture(context.now());
        String zone = context.profile().timeZone();
        if (zone == null || !zone.equals(command.schedule().timeZone())) {
            throw invalid();
        }
        if (context.currentPlan() != null) {
            context.currentPlan().steps().forEach(step -> step.materials().forEach(material -> materials.add(material.id())));
        }
        if (command.materialId() != null && !materials.contains(command.materialId())) {
            throw invalid();
        }
        if (command.planStepId() != null) {
            var step = context.currentPlan() == null ? null : context.currentPlan().steps().stream()
                    .filter(item -> item.id().equals(command.planStepId())).findFirst().orElse(null);
            if (step == null || (command.materialId() != null
                    && step.materials().stream().noneMatch(item -> item.id().equals(command.materialId())))) {
                throw invalid();
            }
        }
    }

    private JsonNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) {
            throw invalid();
        }
        return node;
    }

    private List<UUID> ids(JsonNode node, int max) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : array(node, max)) {
            if (!item.isString()) {
                throw invalid();
            }
            UUID id = ReminderRequests.uuid(item.stringValue(), "id");
            if (ids.contains(id)) {
                throw invalid();
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private AssistantFailure invalid() {
        return new AssistantFailure("INVALID_RESPONSE");
    }
}

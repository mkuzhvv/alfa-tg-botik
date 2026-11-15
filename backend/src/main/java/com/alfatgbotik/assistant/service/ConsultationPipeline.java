package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.model.FormatRepairBudget;
import com.alfatgbotik.assistant.retrieval.AssistantContextBuilder;
import com.alfatgbotik.identity.api.ProfileDto;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@Service
public class ConsultationPipeline {

    private final AssistantContextBuilder contexts;
    private final AssistantModel model;
    private final AssistantResponseValidator validator;
    private final AssistantReplyFormatter formatter;
    private final ProfileFactExtractor profiles;
    private final ReminderInterpreter reminders;
    private final PlanDraftGenerator planner;
    private final JsonMapper json;

    public ConsultationPipeline(AssistantContextBuilder contexts, AssistantModel model, AssistantResponseValidator validator,
                                AssistantReplyFormatter formatter, ProfileFactExtractor profiles, ReminderInterpreter reminders,
                                PlanDraftGenerator planner, JsonMapper json) {
        this.contexts = contexts;
        this.model = model;
        this.validator = validator;
        this.formatter = formatter;
        this.profiles = profiles;
        this.reminders = reminders;
        this.planner = planner;
        this.json = json;
    }

    public Result answer(UUID userId, String message, ConsultationDeadline deadline) {
        var repair = new FormatRepairBudget();
        var base = contexts.base(userId, message);
        var facts = profiles.extract(base.context(), repair, deadline);
        JsonNode patch = facts.patch();
        base = withProfile(base, patch);
        boolean reminder = reminders.applies(base.context());
        boolean plan = PlanIntent.requested(base.context());
        if (plan && reminder) {
            return result(base, ActionResponses.clarify(json, "Что сделать сначала: предложить план или напоминание?", patch), facts.model());
        }
        if (reminder) {
            return result(base, reminders.interpret(base.context(), patch), facts.model());
        }
        if (plan) {
            String goal = PlanIntent.goal(base.context());
            if (goal == null) {
                return result(base, ActionResponses.clarify(json, "Для плана нужна цель. Какую задачу бизнеса хочешь решить?", patch), facts.model());
            }
            return plan(base, goal, patch, repair, deadline);
        }
        if (message.matches("(?isuU).*(?:отмени|удали|перенеси|приостанови|возобнови|измени).*(?:напоминани|план|шаг).*")) {
            return result(base, ActionResponses.clarify(json, "Открой /plan или /reminders и выбери запись кнопкой. Что хочешь изменить?", patch), facts.model());
        }
        if (!patch.isEmpty() && !patch.hasNonNull("currentTask") && !message.matches("(?isuU).*\\b(?:как|зачем|почему|что|подскажи|посоветуй|расскажи|помоги)\\b.*") && !message.contains("?")) {
            return result(base, ActionResponses.clarify(json, "Учту явно сообщённые сведения. Какую задачу бизнеса хочешь разобрать?", patch), facts.model());
        }
        if (base.context().currentPlan() != null && base.context().currentPlan().steps().stream().allMatch(step -> step.status().equals("DONE"))
                && message.matches("(?isuU).*(?:что.*дальше|следующ.*шаг).*")) {
            return result(base, ActionResponses.clarify(json, "Все шаги текущего плана выполнены. Какую новую цель хочешь выбрать?", patch), facts.model());
        }
        var prepared = contexts.prepare(base);
        String correction = null;
        while (true) {
            deadline.remaining();
            var generated = model.generate(prepared.context(), correction, deadline);
            try {
                var response = validator.validate(generated.text(), prepared.context());
                if (!response.profilePatch().isEmpty() && !response.profilePatch().equals(patch)) {
                    throw new AssistantFailure("UNVERIFIED_PROFILE_CHANGE");
                }
                response = withPatch(response, patch);
                return result(prepared, response, generated);
            } catch (AssistantFailure failure) {
                if (!repair.take()) throw failure;
                correction = failure.code();
            }
        }
    }

    public Result generatePlan(UUID userId, String goal, ConsultationDeadline deadline) {
        var base = contexts.base(userId, goal);
        return plan(base, goal, json.createObjectNode(), new FormatRepairBudget(), deadline);
    }

    private Result plan(AssistantContextBuilder.PreparedContext base, String goal, JsonNode patch,
                        FormatRepairBudget repair, ConsultationDeadline deadline) {
        var prepared = contexts.prepare(base);
        var draft = planner.generate(prepared.context(), goal, repair, deadline);
        return result(prepared, withPatch(draft.response(), patch),
                new AssistantModel.ModelReply("", draft.modelName(), draft.modelRevision()));
    }

    private Result result(AssistantContextBuilder.PreparedContext context, AssistantResponse response, AssistantModel.ModelReply generated) {
        AssistantResponse checked = validator.validate(json.writeValueAsString(response), context.context());
        return new Result(checked, formatter.format(checked, context.context()), generated == null ? "rules:actions-1" : generated.modelName(),
                generated == null ? "actions-1" : generated.modelRevision(), context.datasetVersion(), context.profileVersion());
    }

    private AssistantResponse withPatch(AssistantResponse response, JsonNode patch) {
        return new AssistantResponse(response.schemaVersion(), response.status(), response.answer(), response.sourceChunkIds(),
                response.recommendations(), patch, response.planProposal(), response.reminderProposal());
    }

    private AssistantContextBuilder.PreparedContext withProfile(AssistantContextBuilder.PreparedContext base, JsonNode patch) {
        if (patch.isEmpty()) return base;
        ObjectNode profile = json.valueToTree(base.context().profile());
        profile.setAll((ObjectNode) patch);
        AssistantContext context = base.context();
        return new AssistantContextBuilder.PreparedContext(new AssistantContext(context.schemaVersion(), context.now(), context.message(),
                json.treeToValue(profile, ProfileDto.class), context.history(), context.sources(), context.catalog(), context.currentPlan()),
                base.datasetVersion(), base.profileVersion());
    }

    public record Result(AssistantResponse response, String text, String modelName, String modelRevision,
                         String datasetVersion, long profileVersion) { }
}

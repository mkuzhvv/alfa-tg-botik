package com.alfatgbotik.plan.service;

import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.assistant.service.ActionException;
import com.alfatgbotik.assistant.service.AssistantExecution;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.assistant.service.ConsultationPipeline;
import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.api.PlanDto;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.model.PlanReplacement;
import com.alfatgbotik.plan.persistence.PlanGenerationRepository;
import com.alfatgbotik.reminder.api.ReminderRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlanGenerationService {

    private final PlanGenerationRepository requests;
    private final PlanService plans;
    private final UserService users;
    private final ConsultationPipeline pipeline;
    private final AssistantExecution execution;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    public PlanGenerationService(PlanGenerationRepository requests, PlanService plans, UserService users,
                                  ConsultationPipeline pipeline, AssistantExecution execution,
                                  PlatformTransactionManager manager, JsonMapper json, Clock clock) {
        this.requests = requests;
        this.plans = plans;
        this.users = users;
        this.pipeline = pipeline;
        this.execution = execution;
        transactions = new TransactionTemplate(manager);
        this.json = json;
        this.clock = clock;
    }

    public PlanDto generate(UUID userId, UUID key, JsonNode input) {
        JsonNode body = input == null ? json.createObjectNode() : input;
        UUID runId = UUID.randomUUID();
        PlanDto replay = transactions.execute(status -> {
            if (!requests.tryLock(userId, key)) throw new ActionException(409, "REQUEST_IN_PROGRESS");
            var previous = requests.find(userId, key).orElse(null);
            if (previous != null) {
                if (!previous.body().equals(body)) throw new ActionException(409, "IDEMPOTENCY_CONFLICT");
                if (previous.status().equals("SUCCEEDED")) return previous.result();
                if (previous.status().equals("PROCESSING") && previous.leaseUntil().isAfter(clock.instant())) {
                    throw new ActionException(409, "REQUEST_IN_PROGRESS");
                }
            }
            requests.start(userId, key, body, runId, clock.instant());
            return null;
        });
        if (replay != null) return replay;
        try {
            JsonRequest request = new JsonRequest(body, Set.of("goal", "replacePlan"));
            JsonNode goalNode = body.get("goal");
            if (goalNode != null && !goalNode.isNull() && !goalNode.isString()) {
                throw RequestValidationException.malformed("goal", "Expected a string or null");
            }
            String goal = goalNode == null || goalNode.isNull() ? users.getById(userId).profile().currentTask() : goalNode.stringValue();
            if (goal == null || goal.isBlank() || goal.strip().codePointCount(0, goal.strip().length()) > 250) {
                throw RequestValidationException.invalid("goal", "Provide a goal containing 1 to 250 characters");
            }
            goal = goal.strip();
            PlanReplacement replacement = null;
            if (request.contains("replacePlan")) {
                var replace = new JsonRequest(body.get("replacePlan"), Set.of("id", "expectedVersion"));
                replacement = new PlanReplacement(ReminderRequests.uuid(replace.requiredText("id", 36), "replacePlan.id"),
                        replace.requiredNonNegativeLong("expectedVersion"));
            }
            plans.checkGeneration(userId, replacement);
            var deadline = execution.deadline();
            String selectedGoal = goal;
            var generated = execution.run(deadline, () -> pipeline.generatePlan(userId, selectedGoal, deadline));
            AssistantResponse response = generated.response();
            if (response.status() == AssistantResponse.Status.CLARIFY) throw RequestValidationException.invalid("goal", response.answer());
            if (response.status() == AssistantResponse.Status.NOT_FOUND) throw new PlanMaterialsNotFoundException();
            if (response.status() != AssistantResponse.Status.ANSWER || response.planProposal().isNull()) throw new AssistantFailure("INVALID_PLAN_RESPONSE");
            PlanReplacement confirmedReplacement = replacement;
            return transactions.execute(status -> {
                requests.lock(userId, key);
                var current = requests.find(userId, key).orElseThrow();
                if (!current.status().equals("PROCESSING") || !current.runId().equals(runId)) throw new ActionException(409, "REQUEST_IN_PROGRESS");
                Map<UUID, String> reasons = new LinkedHashMap<>();
                response.recommendations().forEach(item -> reasons.put(item.materialId(), item.reason()));
                var saved = plans.saveProposal(userId, json.treeToValue(response.planProposal(), PlanProposal.class), reasons, confirmedReplacement);
                requests.succeed(userId, key, runId, saved, clock.instant());
                return saved;
            });
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> requests.fail(userId, key, runId, clock.instant()));
            throw exception;
        }
    }
}

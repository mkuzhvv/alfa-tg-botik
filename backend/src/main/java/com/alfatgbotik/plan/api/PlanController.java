package com.alfatgbotik.plan.api;

import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.identity.model.AuthenticatedSession;
import com.alfatgbotik.plan.service.PlanService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService plans;
    private final JsonMapper json;

    public PlanController(PlanService plans, JsonMapper json) {
        this.plans = plans;
        this.json = json;
    }

    @GetMapping("/current")
    public JsonNode current(@AuthenticationPrincipal AuthenticatedSession session) {
        return json.valueToTree(plans.getCurrent(session.userId()));
    }

    @GetMapping("/{planId}")
    public PlanDto get(@AuthenticationPrincipal AuthenticatedSession session, @PathVariable String planId) {
        return plans.getById(session.userId(), uuid(planId, "planId"));
    }

    @PatchMapping("/{planId}/steps/{stepId}")
    public PlanDto updateStep(@AuthenticationPrincipal AuthenticatedSession session, @PathVariable String planId,
                              @PathVariable String stepId, @RequestBody JsonNode body) {
        StepUpdateRequest request = StepUpdateRequest.from(body);
        return plans.updateStep(session.userId(), uuid(planId, "planId"), uuid(stepId, "stepId"),
                request.expectedVersion(), request.status());
    }

    @PostMapping("/{planId}/archive")
    public PlanDto archive(@AuthenticationPrincipal AuthenticatedSession session, @PathVariable String planId,
                           @RequestBody JsonNode body) {
        JsonRequest request = new JsonRequest(body, Set.of("expectedVersion"));
        return plans.archive(session.userId(), uuid(planId, "planId"),
                request.requiredNonNegativeLong("expectedVersion"));
    }

    private UUID uuid(String value, String field) {
        if (!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw RequestValidationException.malformed(field, "Expected a UUID");
        }
        return UUID.fromString(value);
    }
}

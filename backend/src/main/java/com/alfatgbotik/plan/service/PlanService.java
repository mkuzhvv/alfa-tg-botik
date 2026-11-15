package com.alfatgbotik.plan.service;

import com.alfatgbotik.catalog.service.MaterialNotFoundException;
import com.alfatgbotik.catalog.service.MaterialService;
import com.alfatgbotik.identity.service.UserNotFoundException;
import com.alfatgbotik.plan.api.PlanDto;
import com.alfatgbotik.plan.model.Plan;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.model.PlanReplacement;
import com.alfatgbotik.plan.model.PlanStatus;
import com.alfatgbotik.plan.model.StepStatus;
import com.alfatgbotik.plan.persistence.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlanService {

    private final PlanRepository plans;
    private final MaterialService materials;
    private final Clock clock;

    public PlanService(PlanRepository plans, MaterialService materials, Clock clock) {
        this.plans = plans;
        this.materials = materials;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PlanDto getCurrent(UUID userId) {
        return plans.findCurrent(userId).orElse(null);
    }

    @Transactional(readOnly = true)
    public PlanDto getById(UUID userId, UUID planId) {
        return plans.findOwned(userId, planId).orElseThrow(PlanNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public void requireReminderStep(UUID userId, UUID stepId, UUID materialId) {
        if (!plans.ownsStep(userId, stepId)) {
            throw new PlanNotFoundException();
        }
        if (materialId != null && !plans.stepContainsMaterial(stepId, materialId)) {
            throw new PlanValidationException("materialId", "Material must belong to the selected step");
        }
    }

    @Transactional
    public PlanDto saveProposal(UUID userId, PlanProposal proposal, Map<UUID, String> recommendationReasons,
                                 PlanReplacement replacement) {
        PlanProposal validated = validate(proposal);
        if (replacement != null && (replacement.id() == null || replacement.expectedVersion() < 0)) {
            throw new PlanValidationException("replacePlan", "Expected a plan ID and non-negative version");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        validated.steps().forEach(step -> ids.addAll(step.materialIds()));
        Map<UUID, String> reasons = new HashMap<>();
        for (UUID id : ids) {
            String reason = recommendationReasons == null ? null : recommendationReasons.get(id);
            if (reason != null) {
                reasons.put(id, text(reason, 300, "recommendationReason"));
            }
        }
        lockUser(userId);
        Plan current = plans.findCurrentHeader(userId).orElse(null);
        checkReplacement(current, replacement);
        String datasetVersion;
        try {
            datasetVersion = materials.requireActiveMaterials(ids);
        } catch (MaterialNotFoundException exception) {
            throw new PlanMaterialsNotFoundException();
        }

        Instant now = current == null ? clock.instant() : updateTime(current);
        if (current != null) {
            plans.archive(current.id(), now);
        }
        UUID planId = UUID.randomUUID();
        plans.insertPlan(planId, userId, validated.goal(), now);
        for (int i = 0; i < validated.steps().size(); i++) {
            PlanProposal.Step step = validated.steps().get(i);
            UUID stepId = UUID.randomUUID();
            plans.insertStep(stepId, planId, i + 1, step.title(), step.description());
            for (int j = 0; j < step.materialIds().size(); j++) {
                UUID materialId = step.materialIds().get(j);
                plans.insertMaterial(stepId, j + 1, materialId, datasetVersion, reasons.get(materialId));
            }
        }
        return getById(userId, planId);
    }

    @Transactional
    public PlanDto updateStep(UUID userId, UUID planId, UUID stepId, long expectedVersion, StepStatus status) {
        requireVersion(expectedVersion);
        if (status == null) {
            throw new PlanValidationException("status", "Field is required");
        }
        lockUser(userId);
        Plan plan = plans.findOwnedHeader(userId, planId).orElseThrow(PlanNotFoundException::new);
        StepStatus existing = plans.findStepStatus(planId, stepId).orElseThrow(PlanNotFoundException::new);
        checkMutable(plan, expectedVersion);
        if (existing != status) {
            plans.updateStep(planId, stepId, status);
            plans.bumpVersion(planId, updateTime(plan));
        }
        return getById(userId, planId);
    }

    @Transactional
    public PlanDto archive(UUID userId, UUID planId, long expectedVersion) {
        requireVersion(expectedVersion);
        lockUser(userId);
        Plan plan = plans.findOwnedHeader(userId, planId).orElseThrow(PlanNotFoundException::new);
        checkMutable(plan, expectedVersion);
        plans.archive(planId, updateTime(plan));
        return getById(userId, planId);
    }

    private PlanProposal validate(PlanProposal proposal) {
        if (proposal == null || proposal.steps() == null || proposal.steps().size() < 3 || proposal.steps().size() > 5) {
            throw new PlanValidationException("steps", "Plan must contain 3 to 5 steps");
        }
        String goal = text(proposal.goal(), 250, "goal");
        List<PlanProposal.Step> steps = new ArrayList<>();
        for (int i = 0; i < proposal.steps().size(); i++) {
            PlanProposal.Step step = proposal.steps().get(i);
            String field = "steps[" + i + "]";
            if (step == null || step.materialIds() == null || step.materialIds().size() < 1
                    || step.materialIds().size() > 2 || step.materialIds().stream().anyMatch(id -> id == null)
                    || new HashSet<>(step.materialIds()).size() != step.materialIds().size()) {
                throw new PlanValidationException(field + ".materialIds", "Expected 1 to 2 distinct material IDs");
            }
            steps.add(new PlanProposal.Step(text(step.title(), 160, field + ".title"),
                    text(step.description(), 600, field + ".description"), List.copyOf(step.materialIds())));
        }
        return new PlanProposal(goal, List.copyOf(steps));
    }

    private String text(String value, int limit, String field) {
        if (value == null || value.isBlank()) {
            throw new PlanValidationException(field, "Field must not be blank");
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) > limit) {
            throw new PlanValidationException(field, "Must contain at most " + limit + " characters");
        }
        return stripped;
    }

    private void checkReplacement(Plan current, PlanReplacement replacement) {
        if (current == null) {
            if (replacement != null) {
                throw PlanConflictException.version();
            }
        } else if (replacement == null) {
            throw PlanConflictException.replacementRequired();
        } else if (!current.id().equals(replacement.id()) || current.version() != replacement.expectedVersion()) {
            throw PlanConflictException.version();
        }
    }

    private void checkMutable(Plan plan, long expectedVersion) {
        if (plan.version() != expectedVersion) {
            throw PlanConflictException.version();
        }
        if (plan.status() == PlanStatus.ARCHIVED) {
            throw PlanConflictException.archived();
        }
    }

    private void requireVersion(long version) {
        if (version < 0) {
            throw new PlanValidationException("expectedVersion", "Must not be negative");
        }
    }

    private void lockUser(UUID userId) {
        if (!plans.lockUser(userId)) {
            throw new UserNotFoundException();
        }
    }

    private Instant updateTime(Plan plan) {
        Instant now = clock.instant();
        return now.isBefore(plan.updatedAt()) ? plan.updatedAt() : now;
    }
}

package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.persistence.ActionProposalRepository;
import com.alfatgbotik.identity.persistence.TelegramContactRepository;
import com.alfatgbotik.plan.model.PlanProposal;
import com.alfatgbotik.plan.model.PlanReplacement;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.reminder.api.ReminderRequests;
import com.alfatgbotik.reminder.service.ReminderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActionProposalService {

    private final ActionProposalRepository proposals;
    private final TelegramContactRepository users;
    private final PlanService plans;
    private final ReminderService reminders;
    private final JsonMapper json;
    private final Clock clock;

    public ActionProposalService(ActionProposalRepository proposals, TelegramContactRepository users, PlanService plans,
                                  ReminderService reminders, JsonMapper json, Clock clock) {
        this.proposals = proposals;
        this.users = users;
        this.plans = plans;
        this.reminders = reminders;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<ActionProposalRepository.Proposal> find(UUID userId, UUID requestId) {
        return proposals.find(userId, requestId);
    }

    @Transactional
    public Confirmation confirm(UUID userId, UUID id, boolean replace) {
        users.lock(userId).orElseThrow(() -> new ActionException(404, "NOT_FOUND"));
        var proposal = proposals.find(userId, id).orElseThrow(() -> new ActionException(404, "NOT_FOUND"));
        if (proposal.status().equals("CONFIRMED")) return new Confirmation(proposal.resultText(), null);
        if (proposal.status().equals("CANCELLED")) return new Confirmation("Это предложение уже отменено.", null);
        if (!proposal.expiresAt().isAfter(clock.instant())) throw new ActionException(409, "PROPOSAL_EXPIRED");
        if (proposal.kind().equals("PLAN")) {
            var current = plans.getCurrent(userId);
            if (!replace && current != null) {
                proposals.awaitReplacement(id, current.id(), current.version());
                return new Confirmation("У тебя уже есть активный план «" + current.goal()
                        + "». Заменить его предложенным? Старый план останется в архиве.", id);
            }
            PlanReplacement replacement = null;
            if (replace) {
                if (!proposal.status().equals("AWAITING_REPLACEMENT") || proposal.replacementId() == null) {
                    throw new ActionException(409, "VERSION_CONFLICT");
                }
                replacement = new PlanReplacement(proposal.replacementId(), proposal.replacementVersion());
            }
            Map<UUID, String> reasons = new LinkedHashMap<>();
            proposal.response().recommendations().forEach(item -> reasons.put(item.materialId(), item.reason()));
            var saved = plans.saveProposal(userId, json.treeToValue(proposal.response().planProposal(), PlanProposal.class), reasons, replacement);
            String text = "План «" + saved.goal() + "» сохранён. Открой /plan, чтобы видеть шаги и отмечать выполнение.";
            proposals.confirm(id, saved, text);
            return new Confirmation(text, null);
        }
        if (replace) throw new ActionException(409, "VERSION_CONFLICT");
        var saved = reminders.create(userId, ReminderRequests.create(proposal.response().reminderProposal()));
        String date = saved.nextFireAt().atZone(ZoneId.of(saved.schedule().timeZone())).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String text = "Напоминание «" + saved.title() + "» сохранено. Ближайшая отправка: " + date
                + " (" + saved.schedule().timeZone() + "). Управление: /reminders.";
        proposals.confirm(id, saved, text);
        return new Confirmation(text, null);
    }

    @Transactional
    public Confirmation cancel(UUID userId, UUID id) {
        users.lock(userId).orElseThrow(() -> new ActionException(404, "NOT_FOUND"));
        var proposal = proposals.find(userId, id).orElseThrow(() -> new ActionException(404, "NOT_FOUND"));
        if (proposal.status().equals("CONFIRMED")) return new Confirmation(proposal.resultText(), null);
        proposals.cancel(id);
        return new Confirmation("Предложение отменено. Сохранённые планы и напоминания не изменены.", null);
    }

    public record Confirmation(String text, UUID replacementProposalId) { }
}

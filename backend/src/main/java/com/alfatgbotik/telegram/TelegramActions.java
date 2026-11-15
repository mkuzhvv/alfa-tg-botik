package com.alfatgbotik.telegram;

import com.alfatgbotik.assistant.service.ActionException;
import com.alfatgbotik.assistant.service.ActionProposalService;
import com.alfatgbotik.common.error.RequestValidationException;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.model.StepStatus;
import com.alfatgbotik.plan.service.PlanConflictException;
import com.alfatgbotik.plan.service.PlanNotFoundException;
import com.alfatgbotik.plan.service.PlanMaterialsNotFoundException;
import com.alfatgbotik.plan.service.PlanValidationException;
import com.alfatgbotik.plan.service.PlanService;
import com.alfatgbotik.reminder.api.ReminderRequests;
import com.alfatgbotik.reminder.model.ReminderStatus;
import com.alfatgbotik.reminder.service.ReminderException;
import com.alfatgbotik.reminder.service.ReminderService;
import com.alfatgbotik.reminder.service.ReminderUpdateCommand;
import com.alfatgbotik.telegram.model.TelegramReply;
import com.alfatgbotik.telegram.model.TelegramReply.Button;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Component
public class TelegramActions {

    private final ActionProposalService proposals;
    private final PlanService plans;
    private final ReminderService reminders;
    private final UserService users;

    public TelegramActions(ActionProposalService proposals, PlanService plans, ReminderService reminders, UserService users) {
        this.proposals = proposals;
        this.plans = plans;
        this.reminders = reminders;
        this.users = users;
    }

    public List<Button> proposalButtons(UUID userId, UUID requestId) {
        return proposals.find(userId, requestId).map(proposal -> List.of(
                Button.action("Сохранить", "ac:" + proposal.id()), Button.action("Не сохранять", "ax:" + proposal.id()))).orElse(List.of());
    }

    public TelegramReply profile(UUID userId, long chatId) {
        var user = users.getById(userId);
        var profile = user.profile();
        String text = "Профиль бизнеса:\nСфера: " + value(profile.industry()) + "\nСтадия: " + value(profile.businessStage())
                + "\nОпыт: " + value(profile.experience()) + "\nТекущая задача: " + value(profile.currentTask())
                + "\nНалоговый режим: " + value(profile.taxRegime()) + "\nЧасовой пояс: " + value(profile.timeZone())
                + "\n\nЧтобы обновить сведения, сообщи их явно, например: «У меня кофейня. Мой часовой пояс Europe/Moscow».";
        return TelegramReply.message(chatId, text, false);
    }

    public TelegramReply plan(UUID userId, long chatId) {
        var plan = plans.getCurrent(userId);
        if (plan == null) return TelegramReply.message(chatId, "Активного плана нет. Напиши: «Составь план для моей цели».", false);
        StringBuilder text = new StringBuilder("Текущий план: ").append(plan.goal());
        List<Button> buttons = new ArrayList<>();
        HashSet<UUID> linked = new HashSet<>();
        for (var step : plan.steps()) {
            text.append("\n\n").append(step.position()).append(step.status() == StepStatus.DONE ? ". ✅ " : ". ⬜ ")
                    .append(step.title()).append("\n").append(shortText(step.description(), 180));
            buttons.add(Button.action((step.status() == StepStatus.DONE ? "Вернуть в работу шаг " : "Готово: шаг ") + step.position(),
                    (step.status() == StepStatus.DONE ? "st:" : "sd:") + step.id() + ":" + Long.toString(plan.version(), 36)));
            for (var material : step.materials()) {
                if (linked.add(material.id())) buttons.add(Button.link(shortText(material.title(), 55), material.url()));
            }
        }
        buttons.add(Button.action("Архивировать план", "pa:" + plan.id() + ":" + Long.toString(plan.version(), 36)));
        text.append("\n\nОтмечай шаги кнопками. Для напоминания напиши, например: «Напомни завтра в 9 по Москве о втором шаге».");
        return TelegramReply.message(chatId, text.toString(), false).withButtons(buttons);
    }

    public TelegramReply reminders(UUID userId, long chatId) {
        var active = reminders.list(userId, ReminderStatus.ACTIVE, 0, 5);
        var paused = reminders.list(userId, ReminderStatus.PAUSED, 0, 5);
        var items = new ArrayList<>(active.items());
        items.addAll(paused.items());
        if (items.isEmpty()) return TelegramReply.message(chatId,
                "Активных и приостановленных напоминаний нет. Напиши, например: «Напомни завтра в 9 по Москве продолжить обучение».", false);
        StringBuilder text = new StringBuilder("Напоминания (до 5 активных и 5 на паузе):");
        List<Button> buttons = new ArrayList<>();
        int position = 0;
        for (var reminder : items) {
            position++;
            text.append("\n\n").append(position).append(". ").append(reminder.title()).append("\n");
            boolean isActive = reminder.status() == ReminderStatus.ACTIVE;
            if (isActive) {
                text.append(reminder.nextFireAt().atZone(ZoneId.of(reminder.schedule().timeZone()))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append(" (").append(reminder.schedule().timeZone()).append(")");
            } else text.append("На паузе");
            String suffix = reminder.id() + ":" + Long.toString(reminder.version(), 36);
            buttons.add(Button.action((isActive ? "Пауза: " : "Возобновить: ") + position, (isActive ? "rp:" : "rr:") + suffix));
            buttons.add(Button.action("Отменить: " + position, "rx:" + suffix));
        }
        return TelegramReply.message(chatId, text.toString(), false).withButtons(buttons);
    }

    public TelegramReply callback(UUID userId, long chatId, String callbackId, String data) {
        String[] parts = data == null ? new String[0] : data.split(":", -1);
        if (parts.length < 2 || parts.length > 3) return notice(callbackId, "Эта кнопка устарела. Открой /help.");
        UUID id = ReminderRequests.uuid(parts[1], "id");
        if (parts.length == 2 && List.of("ac", "ar", "ax").contains(parts[0])) {
            var result = parts[0].equals("ax") ? proposals.cancel(userId, id) : proposals.confirm(userId, id, parts[0].equals("ar"));
            var reply = TelegramReply.callback(callbackId, null, chatId, result.text());
            if (result.replacementProposalId() != null) reply = reply.withButtons(List.of(
                    Button.action("Да, заменить действующий план", "ar:" + id), Button.action("Не заменять", "ax:" + id)));
            return reply;
        }
        if (parts.length != 3) return notice(callbackId, "Эта кнопка устарела. Открой /help.");
        long version;
        try { version = Long.parseLong(parts[2], 36); } catch (NumberFormatException exception) { return notice(callbackId, "Неверная кнопка."); }
        if (version < 0) return notice(callbackId, "Неверная версия кнопки.");
        if (parts[0].equals("sd") || parts[0].equals("st")) {
            var current = plans.getCurrent(userId);
            if (current == null || current.steps().stream().noneMatch(step -> step.id().equals(id))) throw new PlanNotFoundException();
            plans.updateStep(userId, current.id(), id, version, parts[0].equals("sd") ? StepStatus.DONE : StepStatus.TODO);
            return asCallback(plan(userId, chatId), callbackId);
        }
        if (parts[0].equals("pa")) {
            plans.archive(userId, id, version);
            return TelegramReply.callback(callbackId, null, chatId, "План архивирован. Его напоминания продолжают работать; управление — /reminders.");
        }
        ReminderStatus status = switch (parts[0]) { case "rp" -> ReminderStatus.PAUSED; case "rr" -> ReminderStatus.ACTIVE; case "rx" -> ReminderStatus.CANCELLED; default -> null; };
        if (status == null) return notice(callbackId, "Эта кнопка больше не поддерживается.");
        reminders.update(userId, id, new ReminderUpdateCommand(version, null, null, status));
        return asCallback(reminders(userId, chatId), callbackId);
    }

    public TelegramReply rejection(String callbackId, RuntimeException failure) {
        if (failure instanceof ActionException exception) {
            return notice(callbackId, exception.code().equals("PROPOSAL_EXPIRED") ? "Срок предложения истёк. Попроси новое." : "Предложение недоступно или уже изменилось.");
        }
        if (failure instanceof PlanConflictException) {
            return notice(callbackId, "План изменился. Открой /plan или снова нажми «Сохранить» у предложения.");
        }
        if (failure instanceof PlanNotFoundException) {
            return notice(callbackId, "Этот план или шаг недоступен. Открой /plan.");
        }
        if (failure instanceof PlanMaterialsNotFoundException || failure instanceof PlanValidationException) {
            return notice(callbackId, "Данные плана больше недоступны. Попроси новое предложение.");
        }
        if (failure instanceof ReminderException exception) {
            return notice(callbackId, exception.getCode().equals("BOT_START_REQUIRED") ? "Сначала отправь /start, затем повтори подтверждение." : "Напоминание изменилось. Открой /reminders.");
        }
        if (failure instanceof RequestValidationException) {
            return notice(callbackId, "Дата или данные устарели. Создай новое предложение с будущим временем.");
        }
        throw failure;
    }

    private TelegramReply asCallback(TelegramReply reply, String callbackId) {
        return TelegramReply.callback(callbackId, null, reply.chatId(), reply.text()).withButtons(reply.buttons());
    }

    private TelegramReply notice(String callbackId, String text) { return TelegramReply.callback(callbackId, text, null, null); }
    private String value(Object value) { return value == null ? "не указано" : value.toString(); }
    private String shortText(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
    }
}

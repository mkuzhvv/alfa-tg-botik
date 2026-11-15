package com.alfatgbotik.assistant.retrieval;

import com.alfatgbotik.assistant.integration.AssistantPrompt;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantContext.HistoryMessage;
import com.alfatgbotik.assistant.model.AssistantContext.Material;
import com.alfatgbotik.assistant.model.AssistantContext.Source;
import com.alfatgbotik.assistant.persistence.AssistantKnowledgeRepository;
import com.alfatgbotik.assistant.persistence.ConversationRepository;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.model.SearchHit;
import com.alfatgbotik.identity.service.UserService;
import com.alfatgbotik.plan.service.PlanService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssistantContextBuilder {

    private final CatalogSearchService search;
    private final AssistantKnowledgeRepository knowledge;
    private final ConversationRepository conversations;
    private final UserService users;
    private final PlanService plans;
    private final AssistantPrompt prompt;
    private final Clock clock;

    public AssistantContextBuilder(CatalogSearchService search, AssistantKnowledgeRepository knowledge,
                                   ConversationRepository conversations, UserService users, PlanService plans,
                                   AssistantPrompt prompt, Clock clock) {
        this.search = search;
        this.knowledge = knowledge;
        this.conversations = conversations;
        this.users = users;
        this.plans = plans;
        this.prompt = prompt;
        this.clock = clock;
    }

    public PreparedContext prepare(UUID userId, String message) {
        return prepare(base(userId, message));
    }

    public PreparedContext base(UUID userId, String message) {
        var user = users.getById(userId);
        var plan = plans.getCurrent(userId);
        AssistantContext.CurrentPlan currentPlan = plan == null ? null : new AssistantContext.CurrentPlan(plan.id(),
                plan.goal(), plan.status().name(), plan.version(), plan.steps().stream().map(step -> new AssistantContext.Step(
                step.id(), step.position(), step.title(), step.description(), step.status().name(),
                step.materials().stream().map(this::material).toList())).toList(), plan.createdAt(), plan.updatedAt());
        return new PreparedContext(new AssistantContext("0.1.0", clock.instant(), message, user.profile(),
                conversations.history(userId), List.of(), List.of(), currentPlan), null, user.profileVersion());
    }

    public PreparedContext prepare(PreparedContext base) {
        String message = base.context().message();
        var profile = base.context().profile();
        List<HistoryMessage> history = new ArrayList<>(base.context().history());
        String query = message;
        if (base.context().currentPlan() != null && message.matches("(?isuU).*(?:что.*дальше|следующ.*шаг).*")) {
            var plan = base.context().currentPlan();
            query = plan.goal() + "\n" + plan.steps().stream().filter(step -> step.status().equals("TODO"))
                    .findFirst().map(AssistantContext.Step::title).orElse("");
        }
        if (message.codePointCount(0, message.length()) < 100
                && message.matches("(?isuU)^(а\\b|и\\b|как это\\b|что это\\b|почему так\\b|подробнее\\b|расскажи подробнее\\b|можно подробнее\\b).*")) {
            String previous = history.stream().filter(item -> item.role().equals("USER"))
                    .reduce((left, right) -> right).map(HistoryMessage::text).orElse("");
            query = previous.isEmpty() ? message : previous + "\n" + message;
        }
        if (query.codePointCount(0, query.length()) > 4000) {
            query = message;
        }
        var found = search.search(query, 5, 3, 0.35);
        String version = found.index().datasetVersion();
        List<UUID> rankedIds = found.chunks().stream().map(SearchHit::chunkId).toList();
        var available = knowledge.sources(version, rankedIds);
        List<Source> sources = new ArrayList<>();
        for (UUID id : rankedIds) {
            available.stream().filter(source -> source.chunkId().equals(id)).findFirst().ifPresent(source -> {
                if (sources.size() < 3 && sources.stream().filter(s -> s.materialId().equals(source.materialId())).count() < 2) {
                    sources.add(new Source(source.chunkId(), source.materialId(), source.title(), excerpt(source.text(), queryText(history, message))));
                }
            });
        }
        LinkedHashSet<UUID> materialIds = new LinkedHashSet<>();
        found.materials().forEach(hit -> materialIds.add(hit.materialId()));
        sources.forEach(source -> materialIds.add(source.materialId()));
        var cards = knowledge.materials(version, List.copyOf(materialIds));
        List<Material> catalog = materialIds.stream().flatMap(id -> cards.stream().filter(card -> card.id().equals(id)))
                .map(card -> new Material(card.id(), card.title(), card.kind(), shortDescription(card.description()),
                        card.isPaid(), card.duration(), card.url())).toList();
        var currentPlan = base.context().currentPlan();
        AssistantContext context;
        while (true) {
            context = new AssistantContext("0.1.0", clock.instant(), message, profile, List.copyOf(history),
                    List.copyOf(sources), catalog, currentPlan);
            if (prompt.fits(context, "INVALID_RESPONSE_OR_LENGTH")) {
                break;
            }
            if (!history.isEmpty()) {
                history.removeFirst();
            } else if (sources.size() > 1) {
                sources.removeLast();
            } else if (!sources.isEmpty() && sources.getFirst().text().length() > 400) {
                Source first = sources.getFirst();
                sources.set(0, new Source(first.chunkId(), first.materialId(), first.title(), prefix(first.text(), 400)));
            } else {
                throw new AssistantFailure("CONTEXT_TOO_LARGE");
            }
        }
        return new PreparedContext(context, version, base.profileVersion());
    }

    private Material material(MaterialCardDto card) {
        return new Material(card.id(), card.title(), card.kind().name(), card.description(), card.isPaid(), card.duration(), card.url());
    }

    private String shortDescription(String value) {
        return value == null ? null : prefix(value, 200);
    }

    private String prefix(String value, int maxLength) {
        int end = Math.min(value.length(), maxLength);
        if (end < value.length() && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private String queryText(List<HistoryMessage> history, String message) {
        return history.stream().filter(item -> item.role().equals("USER")).map(HistoryMessage::text)
                .reduce("", (left, right) -> right) + " " + message;
    }

    private String excerpt(String text, String query) {
        if (text.length() <= 1100) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        var terms = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]{4,}").matcher(query.toLowerCase(Locale.ROOT))
                .results().map(result -> result.group().substring(0, Math.min(6, result.group().length()))).distinct().toList();
        int bestStart = 0;
        int bestScore = -1;
        for (int start = 0; start < text.length(); start += 300) {
            String window = lower.substring(start, Math.min(text.length(), start + 1100));
            int score = (int) terms.stream().filter(window::contains).count();
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        int end = Math.min(text.length(), bestStart + 1100);
        if (Character.isLowSurrogate(text.charAt(bestStart))) {
            bestStart++;
        }
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(bestStart, end);
    }

    public record PreparedContext(AssistantContext context, String datasetVersion, long profileVersion) {
    }
}

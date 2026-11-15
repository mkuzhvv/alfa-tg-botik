package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.AssistantResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AssistantReplyFormatter {

    public String format(AssistantResponse response, AssistantContext context) {
        StringBuilder result = new StringBuilder(response.answer());
        if (!response.planProposal().isNull()) {
            result.append("\n\nЦель: ").append(response.planProposal().get("goal").stringValue());
            int position = 1;
            for (var step : response.planProposal().get("steps")) {
                result.append("\n\n").append(position++).append(". ").append(step.get("title").stringValue());
                String description = step.get("description").stringValue();
                int count = description.codePointCount(0, description.length());
                result.append("\n").append(description, 0, description.offsetByCodePoints(0, Math.min(count, 180)));
                if (count > 180) result.append("…");
            }
        }
        Map<UUID, String> selected = new LinkedHashMap<>();
        response.recommendations().forEach(item -> selected.put(item.materialId(), item.reason()));
        for (UUID sourceId : response.sourceChunkIds()) {
            var source = context.sources().stream().filter(item -> item.chunkId().equals(sourceId)).findFirst().orElseThrow();
            selected.putIfAbsent(source.materialId(), null);
        }
        if (!selected.isEmpty()) {
            result.append("\n\nМатериалы:");
            selected.forEach((id, reason) -> {
                var material = context.catalog().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
                result.append("\n\n").append(material.title()).append(material.isPaid() ? " · Платно" : " · Бесплатно");
                if (reason != null) {
                    result.append("\n").append(reason);
                }
                result.append("\n").append(material.url());
            });
        }
        if (!response.profilePatch().isEmpty()) {
            result.insert(0, "Профиль обновлён.\n\n");
        }
        if (!response.planProposal().isNull() || !response.reminderProposal().isNull()) {
            result.append("\n\nНажми «Сохранить» или «Не сохранять» под сообщением.");
        }
        if (result.length() > 4096) {
            throw new AssistantFailure("REPLY_TOO_LONG");
        }
        return result.toString();
    }
}

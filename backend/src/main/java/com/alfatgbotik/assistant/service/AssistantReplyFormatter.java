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
        if (!response.planProposal().isNull() || !response.reminderProposal().isNull() || !response.profilePatch().isEmpty()) {
            result.append("\n\nПредложенные изменения не сохранены: подтверждение действий из чата ещё не подключено.");
        }
        if (result.length() > 4096) {
            throw new AssistantFailure("REPLY_TOO_LONG");
        }
        return result.toString();
    }
}

package com.alfatgbotik.assistant.service;

import com.alfatgbotik.assistant.integration.AssistantModel;
import com.alfatgbotik.assistant.model.AssistantResponse;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.retrieval.AssistantContextBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ConsultationPipeline {

    private final AssistantContextBuilder contexts;
    private final AssistantModel model;
    private final AssistantResponseValidator validator;
    private final AssistantReplyFormatter formatter;

    public ConsultationPipeline(AssistantContextBuilder contexts, AssistantModel model,
                                AssistantResponseValidator validator, AssistantReplyFormatter formatter) {
        this.contexts = contexts;
        this.model = model;
        this.validator = validator;
        this.formatter = formatter;
    }

    public Result answer(UUID userId, String message, ConsultationDeadline deadline) {
        deadline.remaining();
        var prepared = contexts.prepare(userId, message);
        String correction = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            deadline.remaining();
            var generated = model.generate(prepared.context(), correction, deadline);
            try {
                AssistantResponse response = validator.validate(generated.text(), prepared.context());
                String text = formatter.format(response, prepared.context());
                deadline.remaining();
                return new Result(response, text, generated.modelName(), generated.modelRevision(), prepared.datasetVersion());
            } catch (AssistantFailure failure) {
                correction = failure.code();
            }
        }
        throw new AssistantFailure("INVALID_RESPONSE");
    }

    public record Result(AssistantResponse response, String text, String modelName, String modelRevision, String datasetVersion) {
    }
}

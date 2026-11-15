package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.service.AssistantFailure;
import org.springframework.stereotype.Component;

@Component
public class OllamaAssistantModel implements AssistantModel {

    private final OllamaChatGateway gateway;
    private final AssistantPrompt prompt;

    public OllamaAssistantModel(OllamaChatGateway gateway, AssistantPrompt prompt) {
        this.gateway = gateway;
        this.prompt = prompt;
    }

    @Override
    public ModelReply generate(AssistantContext context, String correction, ConsultationDeadline deadline) {
        if (!prompt.fits(context, correction)) {
            throw new AssistantFailure("CONTEXT_TOO_LARGE");
        }
        return gateway.complete(prompt.instructions(correction), context, prompt.schema(context), AssistantPrompt.OUTPUT_TOKENS, deadline);
    }
}

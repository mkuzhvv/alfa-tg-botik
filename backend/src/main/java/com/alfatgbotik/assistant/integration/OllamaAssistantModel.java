package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.service.AssistantFailure;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OllamaAssistantModel implements AssistantModel {

    private final LocalOllama ollama;
    private final AssistantPrompt prompt;
    private final String modelName;
    private final String expectedRevision;

    public OllamaAssistantModel(LocalOllama ollama, AssistantPrompt prompt,
                                @Value("${assistant.model:qwen3.5:9b-q4_K_M}") String modelName,
                                @Value("${assistant.model-revision:}") String expectedRevision) {
        this.ollama = ollama;
        this.prompt = prompt;
        this.modelName = modelName;
        this.expectedRevision = expectedRevision;
    }

    @Override
    public ModelReply generate(AssistantContext context, String correction, ConsultationDeadline deadline) {
        if (!prompt.fits(context, correction)) {
            throw new AssistantFailure("CONTEXT_TOO_LARGE");
        }
        String revision = ollama.requireModel(modelName, deadline.remaining());
        if (!expectedRevision.isBlank() && !expectedRevision.equals(revision)) {
            throw new AssistantFailure("MODEL_REVISION_CHANGED");
        }
        OllamaChatOptions options = OllamaChatOptions.builder().model(modelName).format(prompt.schema())
                .disableThinking().temperature(0.0).seed(42).numCtx(AssistantPrompt.CONTEXT_TOKENS)
                .numPredict(AssistantPrompt.OUTPUT_TOKENS).numBatch(512).keepAlive("5m").build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(ollama.baseUrl())
                        .restClientBuilder(ollama.client(deadline.remaining())).build())
                .options(options).retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        try {
            var result = model.call(new Prompt(List.of(new SystemMessage(prompt.instructions(correction)),
                    new UserMessage(prompt.input(context)))));
            deadline.remaining();
            if (result == null || result.getResult() == null || result.getResult().getOutput() == null) {
                throw new AssistantFailure("EMPTY_MODEL_RESPONSE");
            }
            return new ModelReply(result.getResult().getOutput().getText(), modelName, revision);
        } catch (AssistantFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new AssistantFailure("MODEL_UNAVAILABLE");
        }
    }
}

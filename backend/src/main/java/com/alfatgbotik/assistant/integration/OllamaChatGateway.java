package com.alfatgbotik.assistant.integration;

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
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class OllamaChatGateway {

    private final LocalOllama ollama;
    private final JsonMapper json;
    private final String modelName;
    private final String expectedRevision;

    public OllamaChatGateway(LocalOllama ollama, JsonMapper json,
                             @Value("${assistant.model:qwen3.5:9b-q4_K_M}") String modelName,
                             @Value("${assistant.model-revision:}") String expectedRevision) {
        this.ollama = ollama;
        this.json = json;
        this.modelName = modelName;
        this.expectedRevision = expectedRevision;
    }

    public AssistantModel.ModelReply complete(String instructions, Object input, Map<?, ?> schema, int outputTokens,
                                              ConsultationDeadline deadline) {
        String body = json.writeValueAsString(input);
        int bytes = (instructions + body + json.writeValueAsString(schema)).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > AssistantPrompt.CONTEXT_TOKENS - outputTokens - 1024) {
            throw new AssistantFailure("CONTEXT_TOO_LARGE");
        }
        String revision = ollama.requireModel(modelName, deadline.remaining());
        if (!expectedRevision.isBlank() && !expectedRevision.equals(revision)) {
            throw new AssistantFailure("MODEL_REVISION_CHANGED");
        }
        OllamaChatOptions options = OllamaChatOptions.builder().model(modelName).format(schema).disableThinking()
                .temperature(0.0).seed(42).numCtx(AssistantPrompt.CONTEXT_TOKENS).numPredict(outputTokens)
                .numBatch(512).keepAlive("5m").build();
        OllamaChatModel model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(ollama.baseUrl())
                        .restClientBuilder(ollama.client(deadline.remaining())).build()).options(options)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        try {
            var result = model.call(new Prompt(List.of(new SystemMessage(instructions), new UserMessage(body))));
            deadline.remaining();
            if (result == null || result.getResult() == null || result.getResult().getOutput() == null
                    || "length".equalsIgnoreCase(result.getResult().getMetadata().getFinishReason())) {
                throw new AssistantFailure("INCOMPLETE_MODEL_RESPONSE");
            }
            return new AssistantModel.ModelReply(result.getResult().getOutput().getText(), modelName, revision);
        } catch (AssistantFailure failure) {
            throw failure;
        } catch (Exception exception) {
            throw new AssistantFailure("MODEL_UNAVAILABLE");
        }
    }
}

package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.service.KnowledgeUnavailableException;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class OllamaCatalogEmbeddings implements CatalogEmbeddings {

    private static final String MODEL = "embeddinggemma:300m";
    private final LocalOllama ollama;
    private final OllamaEmbeddingModel model;

    public OllamaCatalogEmbeddings(LocalOllama ollama) {
        this.ollama = ollama;
        model = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(ollama.baseUrl())
                        .restClientBuilder(ollama.client(Duration.ofSeconds(60))).build())
                .options(OllamaEmbeddingOptions.builder().model(MODEL).truncate(false).numCtx(2048)
                        .numBatch(2048).keepAlive("5m").build()).build();
    }

    @Override
    public EmbeddingModelInfo describe() {
        try {
            return new EmbeddingModelInfo(MODEL, ollama.requireModel(MODEL, Duration.ofSeconds(5)), DIMENSIONS);
        } catch (Exception exception) {
            throw new KnowledgeUnavailableException("Local embedding model is unavailable");
        }
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        try {
            return model.embed(inputs);
        } catch (Exception exception) {
            throw new KnowledgeUnavailableException("Local embedding request failed");
        }
    }
}

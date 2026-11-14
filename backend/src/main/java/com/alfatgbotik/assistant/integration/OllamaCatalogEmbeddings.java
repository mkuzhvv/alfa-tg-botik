package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.service.KnowledgeUnavailableException;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Component
public class OllamaCatalogEmbeddings implements CatalogEmbeddings {

    private static final String MODEL = "embeddinggemma:300m";
    private final RestClient metadataClient;
    private final OllamaEmbeddingModel model;

    public OllamaCatalogEmbeddings(@Value("${knowledge.ollama-base-url:http://localhost:11434}") String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1", "[::1]", "::1",
                "host.docker.internal").contains(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Knowledge embeddings require a local Ollama URL");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI target) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI target, SocketAddress address, IOException failure) {
                    }
                }).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(60));
        RestClient.Builder rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requests);
        metadataClient = rest.clone().build();
        model = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(rest).build())
                .options(OllamaEmbeddingOptions.builder().model(MODEL).truncate(false).numCtx(2048)
                        .numBatch(2048).keepAlive("5m").build()).build();
    }

    @Override
    public EmbeddingModelInfo describe() {
        try {
            JsonNode response = metadataClient.get().uri("/api/tags").retrieve().body(JsonNode.class);
            if (response != null && response.path("models").isArray()) {
                for (JsonNode item : response.get("models")) {
                    if (MODEL.equals(item.path("name").stringValue()) && item.path("size").longValue() > 1_000_000
                            && item.path("remote_host").stringValue("").isEmpty()
                            && item.path("remote_model").stringValue("").isEmpty()) {
                        String revision = item.path("digest").stringValue();
                        if (revision != null && revision.matches("(sha256:)?[0-9a-f]{64}")) {
                            return new EmbeddingModelInfo(MODEL, revision, DIMENSIONS);
                        }
                    }
                }
            }
        } catch (Exception exception) {
            throw new KnowledgeUnavailableException("Local Ollama is unavailable");
        }
        throw new KnowledgeUnavailableException("Local embeddinggemma:300m is not installed");
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

package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.assistant.service.AssistantFailure;
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
public class LocalOllama {

    private final String baseUrl;
    private final HttpClient http;

    public LocalOllama(@Value("${knowledge.ollama-base-url:http://localhost:11434}") String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1", "[::1]", "::1",
                "host.docker.internal").contains(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("AI requires a local Ollama URL");
        }
        this.baseUrl = baseUrl;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI target) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI target, SocketAddress address, IOException failure) {
                    }
                }).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public RestClient.Builder client(Duration timeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requests);
    }

    public String requireModel(String name, Duration timeout) {
        try {
            JsonNode response = client(timeout).build().get().uri("/api/tags").retrieve().body(JsonNode.class);
            if (response != null && response.path("models").isArray()) {
                for (JsonNode item : response.get("models")) {
                    String digest = item.path("digest").stringValue("");
                    if (name.equals(item.path("name").stringValue()) && item.path("size").longValue() > 1_000_000
                            && item.path("remote_host").stringValue("").isEmpty()
                            && item.path("remote_model").stringValue("").isEmpty()
                            && "gguf".equals(item.path("details").path("format").stringValue())
                            && digest.matches("(sha256:)?[0-9a-f]{64}")) {
                        return digest;
                    }
                }
            }
        } catch (Exception exception) {
            throw new AssistantFailure("MODEL_UNAVAILABLE");
        }
        throw new AssistantFailure("MODEL_NOT_INSTALLED");
    }
}

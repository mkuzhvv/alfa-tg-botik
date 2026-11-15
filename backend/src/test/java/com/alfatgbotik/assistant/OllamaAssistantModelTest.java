package com.alfatgbotik.assistant;

import com.alfatgbotik.assistant.integration.AssistantPrompt;
import com.alfatgbotik.assistant.integration.LocalOllama;
import com.alfatgbotik.assistant.integration.OllamaAssistantModel;
import com.alfatgbotik.assistant.integration.OllamaChatGateway;
import com.alfatgbotik.assistant.model.AssistantContext;
import com.alfatgbotik.assistant.model.ConsultationDeadline;
import com.alfatgbotik.assistant.service.AssistantFailure;
import com.alfatgbotik.identity.api.ProfileDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaAssistantModelTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sendsSpringAiChatRequestWithSchemaAndDisabledThinking() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(received, calls, 200);
        try {
            var result = model(server, "").generate(context(), null, new ConsultationDeadline(Duration.ofSeconds(5)));
            assertThat(result.text()).isEqualTo("stub reply");
            assertThat(result.modelRevision()).isEqualTo("a".repeat(64));
            JsonNode request = received.get();
            assertThat(request.get("model").stringValue()).isEqualTo("qwen3.5:9b-q4_K_M");
            assertThat(request.get("think").booleanValue()).isFalse();
            assertThat(request.get("options").get("num_ctx").intValue()).isEqualTo(16384);
            assertThat(request.get("options").get("seed").intValue()).isEqualTo(42);
            assertThat(request.get("format").get("required").size()).isEqualTo(8);
            assertThat(request.get("messages").get(0).get("role").stringValue()).isEqualTo("system");
            assertThat(json.readTree(request.get("messages").get(1).get("content").stringValue()).get("now").stringValue())
                    .isEqualTo("2026-11-15T10:00:00Z");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsChangedRevisionAndDoesNotRetryTransportErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), calls, 500);
        try {
            assertThatThrownBy(() -> model(server, "b".repeat(64)).generate(context(), null,
                    new ConsultationDeadline(Duration.ofSeconds(5)))).isInstanceOfSatisfying(AssistantFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("MODEL_REVISION_CHANGED"));
            assertThat(calls.get()).isZero();
            assertThatThrownBy(() -> model(server, "").generate(context(), null,
                    new ConsultationDeadline(Duration.ofSeconds(5)))).isInstanceOfSatisfying(AssistantFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("MODEL_UNAVAILABLE"));
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private OllamaAssistantModel model(HttpServer server, String revision) throws Exception {
        return new OllamaAssistantModel(new OllamaChatGateway(new LocalOllama("http://127.0.0.1:" + server.getAddress().getPort()),
                json, "qwen3.5:9b-q4_K_M", revision), new AssistantPrompt(json));
    }

    private HttpServer server(AtomicReference<JsonNode> received, AtomicInteger calls, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = ("{\"models\":[{\"name\":\"qwen3.5:9b-q4_K_M\",\"size\":6500000000,\"digest\":\""
                    + "a".repeat(64) + "\",\"details\":{\"format\":\"gguf\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = (status == 200
                    ? "{\"model\":\"qwen3.5:9b-q4_K_M\",\"created_at\":\"2026-11-15T10:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"stub reply\"},\"done\":true,\"done_reason\":\"stop\"}"
                    : "{\"error\":\"private response body\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private AssistantContext context() {
        return new AssistantContext("0.1.0", Instant.parse("2026-11-15T10:00:00Z"), "Вопрос",
                new ProfileDto(null, null, null, null, null, null), List.of(), List.of(), List.of(), null);
    }
}

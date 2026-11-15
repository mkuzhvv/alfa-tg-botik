package com.alfatgbotik.catalog;

import com.alfatgbotik.assistant.integration.LocalOllama;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaCatalogEmbeddingsTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://192.0.2.1:11434", "http://localhost:11434/proxy",
            "http://user:password@localhost:11434", "http://localhost:11434?target=external"})
    void rejectsRemoteAndAmbiguousModelEndpoints(String url) {
        assertThatThrownBy(() -> new LocalOllama(url)).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.alfatgbotik.assistant.integration;

import com.alfatgbotik.catalog.model.EmbeddingModelInfo;

import java.util.List;

public interface CatalogEmbeddings {

    int DIMENSIONS = 768;
    String FORMAT_VERSION = "embeddinggemma-v1";

    EmbeddingModelInfo describe();

    List<float[]> embed(List<String> inputs);

    static String document(String title, String text) {
        return "title: " + title + " | text: " + text;
    }

    static String query(String text) {
        return "task: search result | query: " + text;
    }
}

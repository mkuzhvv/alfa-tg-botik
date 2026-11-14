package com.alfatgbotik.catalog.model;

import java.util.UUID;

public record CatalogIndex(UUID id, String datasetVersion, EmbeddingModelInfo model, int vectorCount) {
}

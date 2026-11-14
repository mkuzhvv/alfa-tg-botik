package com.alfatgbotik.catalog.model;

import java.util.UUID;

public record CatalogChunk(UUID chunkId, UUID materialId, int order, String heading, String text,
                           String contentHash, boolean externalTextAllowed) {
}

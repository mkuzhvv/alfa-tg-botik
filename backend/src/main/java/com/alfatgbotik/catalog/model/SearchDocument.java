package com.alfatgbotik.catalog.model;

import java.util.UUID;

public record SearchDocument(UUID documentId, UUID materialId, UUID chunkId, String text, float[] embedding) {
}

package com.alfatgbotik.catalog.model;

import java.util.UUID;

public record SearchHit(UUID materialId, UUID chunkId, double score) {
}

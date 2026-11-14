package com.alfatgbotik.catalog.model;

import java.time.Instant;

public record DatasetManifest(String schemaVersion, String datasetVersion, Instant createdAt, String sourceSnapshot,
                              int materialsCount, int chunksCount, String materialsSha256, String chunksSha256) {
}

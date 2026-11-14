package com.alfatgbotik.catalog.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CatalogMaterial(UUID materialId, MaterialKind sourceType, UUID sourceId, String title,
                              String description, String url, boolean isPaid, Long sourceCost,
                              boolean answerAllowed, boolean externalMetadataAllowed, String duration,
                              List<String> topics, List<String> industries, List<UUID> parentMaterialIds,
                              Instant sourceUpdatedAt) {
}

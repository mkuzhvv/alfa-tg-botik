package com.alfatgbotik.catalog.model;

import java.util.List;

public record CatalogDataset(DatasetManifest manifest, List<CatalogMaterial> materials, List<CatalogChunk> chunks) {
}

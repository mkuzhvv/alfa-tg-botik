package com.alfatgbotik.assistant.retrieval;

import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.model.SearchHit;

import java.util.List;

public record CatalogSearchResult(CatalogIndex index, List<SearchHit> chunks, List<SearchHit> materials) {
}

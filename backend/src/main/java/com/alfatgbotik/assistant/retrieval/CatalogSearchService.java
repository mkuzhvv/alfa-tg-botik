package com.alfatgbotik.assistant.retrieval;

import com.alfatgbotik.assistant.integration.CatalogEmbeddings;
import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.persistence.CatalogRepository;
import com.alfatgbotik.catalog.service.CatalogImportService;
import com.alfatgbotik.catalog.service.KnowledgeUnavailableException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CatalogSearchService {

    private final CatalogRepository catalog;
    private final CatalogEmbeddings embeddings;

    public CatalogSearchService(CatalogRepository catalog, CatalogEmbeddings embeddings) {
        this.catalog = catalog;
        this.embeddings = embeddings;
    }

    public CatalogSearchResult search(String query, int chunkLimit, int materialLimit, double minimumScore) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 4000
                || chunkLimit < 1 || chunkLimit > 8 || materialLimit < 1 || materialLimit > 3
                || !Double.isFinite(minimumScore) || minimumScore < 0 || minimumScore > 1) {
            throw new IllegalArgumentException("Invalid search parameters");
        }
        CatalogIndex index = catalog.activeIndex()
                .orElseThrow(() -> new KnowledgeUnavailableException("No active knowledge index"));
        verifyModel(index);
        List<float[]> vectors = embeddings.embed(List.of(CatalogEmbeddings.query(query.strip())));
        CatalogImportService.validateVectors(vectors, 1);
        verifyModel(index);
        return new CatalogSearchResult(index,
                catalog.nearest(index.id(), vectors.getFirst(), true, chunkLimit, minimumScore),
                catalog.nearest(index.id(), vectors.getFirst(), false, materialLimit, minimumScore));
    }

    private void verifyModel(CatalogIndex index) {
        if (!index.model().equals(embeddings.describe())) {
            throw new KnowledgeUnavailableException("Embedding model changed; rebuild the knowledge index");
        }
    }
}

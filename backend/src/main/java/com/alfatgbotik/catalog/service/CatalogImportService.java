package com.alfatgbotik.catalog.service;

import com.alfatgbotik.assistant.integration.CatalogEmbeddings;
import com.alfatgbotik.catalog.model.CatalogDataset;
import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.model.CatalogMaterial;
import com.alfatgbotik.catalog.model.DatasetManifest;
import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.model.SearchDocument;
import com.alfatgbotik.catalog.persistence.CatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private final DatasetReader reader;
    private final CatalogRepository catalog;
    private final CatalogEmbeddings embeddings;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CatalogImportService(DatasetReader reader, CatalogRepository catalog, CatalogEmbeddings embeddings,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.reader = reader;
        this.catalog = catalog;
        this.embeddings = embeddings;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CatalogIndex importDataset(Path directory) {
        CatalogDataset dataset = reader.read(directory);
        verifyExisting(dataset.manifest());
        EmbeddingModelInfo model = embeddings.describe();
        requireModel(model);
        var existing = catalog.findIndex(dataset.manifest().datasetVersion(), model);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<SearchDocument> documents = buildDocuments(dataset);
        List<SearchDocument> indexed = new ArrayList<>(documents.size());
        for (int start = 0; start < documents.size(); start += 16) {
            List<SearchDocument> batch = documents.subList(start, Math.min(start + 16, documents.size()));
            List<float[]> vectors = embeddings.embed(batch.stream().map(SearchDocument::text).toList());
            validateVectors(vectors, batch.size());
            for (int i = 0; i < batch.size(); i++) {
                SearchDocument document = batch.get(i);
                indexed.add(new SearchDocument(document.documentId(), document.materialId(), document.chunkId(),
                        document.text(), vectors.get(i)));
            }
            if (indexed.size() % 256 == 0 || indexed.size() == documents.size()) {
                log.info("Catalog embeddings prepared (dataset={}, completed={}, total={})",
                        dataset.manifest().datasetVersion(), indexed.size(), documents.size());
            }
        }
        if (!model.equals(embeddings.describe())) {
            throw new KnowledgeUnavailableException("Embedding model changed during indexing");
        }
        return transactions.execute(transaction -> {
            catalog.lockImport();
            boolean datasetExists = verifyExisting(dataset.manifest());
            var concurrent = catalog.findIndex(dataset.manifest().datasetVersion(), model);
            if (concurrent.isPresent()) {
                return concurrent.get();
            }
            if (!datasetExists) {
                catalog.insertDataset(dataset, clock.instant());
            }
            CatalogIndex index = new CatalogIndex(UUID.randomUUID(), dataset.manifest().datasetVersion(), model,
                    indexed.size());
            catalog.insertIndex(index, indexed, clock.instant());
            catalog.activate(index.id());
            return index;
        });
    }

    private boolean verifyExisting(DatasetManifest manifest) {
        var existing = catalog.findDataset(manifest.datasetVersion());
        if (existing.isPresent() && (!existing.get().materialsSha256().equals(manifest.materialsSha256())
                || !existing.get().chunksSha256().equals(manifest.chunksSha256()))) {
            throw new DatasetImportException("manifest.json:datasetVersion", "Version already has different files");
        }
        return existing.isPresent();
    }

    private List<SearchDocument> buildDocuments(CatalogDataset dataset) {
        List<SearchDocument> result = new ArrayList<>();
        Map<UUID, CatalogMaterial> materials = new HashMap<>();
        for (CatalogMaterial material : dataset.materials()) {
            materials.put(material.materialId(), material);
            List<String> labels = new ArrayList<>(material.topics());
            labels.addAll(material.industries());
            result.add(new SearchDocument(material.materialId(), material.materialId(), null,
                    CatalogEmbeddings.document(material.title(), material.description() == null
                            ? "\n" + String.join(", ", labels)
                            : material.description() + "\n" + String.join(", ", labels)), null));
        }
        for (var chunk : dataset.chunks()) {
            CatalogMaterial material = materials.get(chunk.materialId());
            result.add(new SearchDocument(chunk.chunkId(), chunk.materialId(), chunk.chunkId(),
                    CatalogEmbeddings.document(material.title(), chunk.text()), null));
        }
        return result;
    }

    private void requireModel(EmbeddingModelInfo model) {
        if (!"embeddinggemma:300m".equals(model.name()) || model.dimensions() != CatalogEmbeddings.DIMENSIONS) {
            throw new KnowledgeUnavailableException("Index requires the ML-selected embeddinggemma model and dimension");
        }
    }

    public static void validateVectors(List<float[]> vectors, int expected) {
        if (vectors == null || vectors.size() != expected) {
            throw new KnowledgeUnavailableException("Embedding count mismatch");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != CatalogEmbeddings.DIMENSIONS) {
                throw new KnowledgeUnavailableException("Embedding dimension mismatch");
            }
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new KnowledgeUnavailableException("Embedding contains a non-finite value");
                }
                norm += (double) value * value;
            }
            if (norm == 0) {
                throw new KnowledgeUnavailableException("Embedding must not be zero");
            }
        }
    }
}

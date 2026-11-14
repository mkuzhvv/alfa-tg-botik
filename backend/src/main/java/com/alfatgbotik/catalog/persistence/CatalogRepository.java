package com.alfatgbotik.catalog.persistence;

import com.alfatgbotik.assistant.integration.CatalogEmbeddings;
import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.model.CatalogDataset;
import com.alfatgbotik.catalog.model.CatalogIndex;
import com.alfatgbotik.catalog.model.DatasetManifest;
import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.model.MaterialKind;
import com.alfatgbotik.catalog.model.SearchDocument;
import com.alfatgbotik.catalog.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public CatalogRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<DatasetManifest> findDataset(String version) {
        return jdbc.query("SELECT * FROM knowledge.datasets WHERE version = ?", (row, number) ->
                new DatasetManifest("0.1.0", row.getString("version"), row.getTimestamp("created_at").toInstant(),
                        row.getString("source_snapshot"), row.getInt("materials_count"), row.getInt("chunks_count"),
                        row.getString("materials_sha256"), row.getString("chunks_sha256")), version).stream().findFirst();
    }

    public Optional<CatalogIndex> findIndex(String version, EmbeddingModelInfo model) {
        return jdbc.query("""
                SELECT * FROM knowledge.search_indexes WHERE dataset_version = ? AND model_name = ?
                    AND model_revision = ? AND format_version = ?
                """, this::index, version, model.name(), model.revision(), CatalogEmbeddings.FORMAT_VERSION)
                .stream().findFirst();
    }

    public Optional<CatalogIndex> activeIndex() {
        return jdbc.query("""
                SELECT i.* FROM knowledge.catalog_state s JOIN knowledge.search_indexes i ON i.id = s.active_index_id
                WHERE s.singleton
                """, this::index).stream().findFirst();
    }

    public Optional<MaterialCardDto> findMaterial(UUID id) {
        return jdbc.query("""
                SELECT m.* FROM knowledge.materials m
                JOIN knowledge.search_indexes i ON i.dataset_version = m.dataset_version
                JOIN knowledge.catalog_state s ON s.active_index_id = i.id
                WHERE s.singleton AND m.id = ?
                """, (row, number) -> new MaterialCardDto(row.getObject("id", UUID.class), row.getString("title"),
                MaterialKind.valueOf(row.getString("source_type")), row.getString("description"), null,
                row.getBoolean("is_paid"), row.getString("duration"), row.getString("url")), id).stream().findFirst();
    }

    public List<UUID> findMaterialIds(String datasetVersion, Set<UUID> ids) {
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT id FROM knowledge.materials WHERE dataset_version = :version AND id IN (:ids)
                """, Map.of("version", datasetVersion, "ids", ids), (row, number) ->
                row.getObject("id", UUID.class));
    }

    public void lockImport() {
        jdbc.execute("SELECT pg_advisory_xact_lock(714205)");
    }

    public void insertDataset(CatalogDataset dataset, Instant now) {
        DatasetManifest manifest = dataset.manifest();
        jdbc.update("""
                INSERT INTO knowledge.datasets (version, created_at, source_snapshot, materials_count, chunks_count,
                    materials_sha256, chunks_sha256, imported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, manifest.datasetVersion(), Timestamp.from(manifest.createdAt()), manifest.sourceSnapshot(),
                manifest.materialsCount(), manifest.chunksCount(), manifest.materialsSha256(),
                manifest.chunksSha256(), Timestamp.from(now));
        jdbc.batchUpdate("""
                INSERT INTO knowledge.materials (dataset_version, id, source_type, source_id, title, description, url,
                    is_paid, source_cost, answer_allowed, external_metadata_allowed, duration,
                    topics, industries, parent_material_ids, source_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """, dataset.materials(), 500, (statement, material) -> {
            statement.setString(1, manifest.datasetVersion());
            statement.setObject(2, material.materialId());
            statement.setString(3, material.sourceType().name());
            statement.setObject(4, material.sourceId());
            statement.setString(5, material.title());
            statement.setString(6, material.description());
            statement.setString(7, material.url());
            statement.setBoolean(8, material.isPaid());
            statement.setObject(9, material.sourceCost());
            statement.setBoolean(10, material.answerAllowed());
            statement.setBoolean(11, material.externalMetadataAllowed());
            statement.setString(12, material.duration());
            statement.setString(13, json.writeValueAsString(material.topics()));
            statement.setString(14, json.writeValueAsString(material.industries()));
            statement.setString(15, json.writeValueAsString(material.parentMaterialIds()));
            statement.setTimestamp(16, material.sourceUpdatedAt() == null ? null : Timestamp.from(material.sourceUpdatedAt()));
        });
        jdbc.batchUpdate("""
                INSERT INTO knowledge.chunks (dataset_version, id, material_id, position, heading, text,
                    content_hash, external_text_allowed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, dataset.chunks(), 500, (statement, chunk) -> {
            statement.setString(1, manifest.datasetVersion());
            statement.setObject(2, chunk.chunkId());
            statement.setObject(3, chunk.materialId());
            statement.setInt(4, chunk.order());
            statement.setString(5, chunk.heading());
            statement.setString(6, chunk.text());
            statement.setString(7, chunk.contentHash());
            statement.setBoolean(8, chunk.externalTextAllowed());
        });
    }

    public void insertIndex(CatalogIndex index, List<SearchDocument> documents, Instant now) {
        jdbc.update("""
                INSERT INTO knowledge.search_indexes (id, dataset_version, model_name, model_revision, dimensions,
                    format_version, vector_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, index.id(), index.datasetVersion(), index.model().name(), index.model().revision(),
                index.model().dimensions(), CatalogEmbeddings.FORMAT_VERSION, documents.size(), Timestamp.from(now));
        jdbc.batchUpdate("""
                INSERT INTO knowledge.search_vectors (index_id, dataset_version, document_id, material_id, chunk_id,
                    embedding) VALUES (?, ?, ?, ?, ?, ?::public.vector)
                """, documents, 500, (statement, document) -> {
            statement.setObject(1, index.id());
            statement.setString(2, index.datasetVersion());
            statement.setObject(3, document.documentId());
            statement.setObject(4, document.materialId());
            statement.setObject(5, document.chunkId());
            statement.setString(6, Arrays.toString(document.embedding()));
        });
    }

    public void activate(UUID indexId) {
        jdbc.update("""
                INSERT INTO knowledge.catalog_state (singleton, active_index_id) VALUES (TRUE, ?)
                ON CONFLICT (singleton) DO UPDATE SET active_index_id = EXCLUDED.active_index_id
                """, indexId);
    }

    public List<SearchHit> nearest(UUID indexId, float[] vector, boolean chunks, int limit, double minimumScore) {
        return jdbc.query("""
                SELECT material_id, chunk_id, 1 - (embedding <=> ?::public.vector) AS score
                FROM knowledge.search_vectors
                WHERE index_id = ? AND (chunk_id IS NOT NULL) = ?
                    AND 1 - (embedding <=> ?::public.vector) >= ?
                ORDER BY embedding <=> ?::public.vector, document_id LIMIT ?
                """, (row, number) -> new SearchHit(row.getObject("material_id", UUID.class),
                row.getObject("chunk_id", UUID.class), row.getDouble("score")), Arrays.toString(vector), indexId,
                chunks, Arrays.toString(vector), minimumScore, Arrays.toString(vector), limit);
    }

    private CatalogIndex index(ResultSet row, int number) throws SQLException {
        return new CatalogIndex(row.getObject("id", UUID.class), row.getString("dataset_version"),
                new EmbeddingModelInfo(row.getString("model_name"), row.getString("model_revision"),
                        row.getInt("dimensions")), row.getInt("vector_count"));
    }
}

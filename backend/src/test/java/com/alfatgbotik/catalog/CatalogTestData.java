package com.alfatgbotik.catalog;

import com.alfatgbotik.catalog.service.DatasetReader;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

final class CatalogTestData {

    final JsonMapper json;
    final Path directory;
    final String version;
    final UUID freeId;
    final UUID paidId;
    final ObjectNode free;
    final ObjectNode paid;
    final ObjectNode chunk;
    final ArrayNode materials;
    final ArrayNode chunks;

    CatalogTestData(JsonMapper json, Path directory, String version) throws Exception {
        this.json = json;
        this.directory = directory;
        this.version = version;
        Files.createDirectories(directory);
        free = material("Free material", null, true);
        paid = material("Paid material", 1000L, false);
        freeId = UUID.fromString(free.get("materialId").stringValue());
        paidId = UUID.fromString(paid.get("materialId").stringValue());
        chunk = json.createObjectNode();
        chunk.put("schemaVersion", "0.1.0");
        chunk.put("chunkId", DatasetReader.uuid5(UUID.fromString("a1fa0000-0000-4000-8000-000000000002"),
                freeId + ":1").toString());
        chunk.put("materialId", freeId.toString());
        chunk.put("order", 1);
        chunk.putNull("heading");
        chunk.put("text", "Free educational text for testing the allowed knowledge import.");
        chunk.put("contentHash", DatasetReader.hash(chunk.get("text").stringValue().getBytes(StandardCharsets.UTF_8)));
        chunk.put("externalTextAllowed", false);
        materials = json.createArrayNode().add(free).add(paid);
        chunks = json.createArrayNode().add(chunk);
        write();
    }

    void write() throws Exception {
        StringBuilder materialLines = new StringBuilder();
        for (var row : materials) {
            materialLines.append(json.writeValueAsString(row)).append('\n');
        }
        StringBuilder chunkLines = new StringBuilder();
        for (var row : chunks) {
            chunkLines.append(json.writeValueAsString(row)).append('\n');
        }
        Files.writeString(directory.resolve("materials.jsonl"), materialLines, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("chunks.jsonl"), chunkLines, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "schemaVersion", "0.1.0", "datasetVersion", version, "createdAt", "2026-11-15T00:00:00Z",
                "sourceSnapshot", "sha256:" + "a".repeat(64), "materialsCount", materials.size(),
                "chunksCount", chunks.size(), "materialsSha256", DatasetReader.hash(materialLines.toString()
                        .getBytes(StandardCharsets.UTF_8)), "chunksSha256", DatasetReader.hash(chunkLines.toString()
                        .getBytes(StandardCharsets.UTF_8)))));
    }

    private ObjectNode material(String title, Long cost, boolean answerAllowed) {
        UUID sourceId = UUID.randomUUID();
        ObjectNode row = json.createObjectNode();
        row.put("schemaVersion", "0.1.0");
        row.put("materialId", DatasetReader.uuid5(UUID.fromString("a1fa0000-0000-4000-8000-000000000001"),
                "COURSE:" + sourceId).toString());
        row.put("sourceType", "COURSE");
        row.put("sourceId", sourceId.toString());
        row.put("title", title);
        row.put("description", "Description of the educational material");
        row.put("url", "https://example.test/material/" + sourceId);
        row.put("isPaid", cost != null && cost > 0);
        if (cost == null) {
            row.putNull("sourceCost");
        } else {
            row.put("sourceCost", cost);
        }
        row.put("answerAllowed", answerAllowed);
        row.put("externalMetadataAllowed", false);
        row.putNull("duration");
        row.putArray("topics");
        row.putArray("industries");
        row.putArray("parentMaterialIds");
        row.putNull("sourceUpdatedAt");
        return row;
    }
}

package com.alfatgbotik.catalog;

import com.alfatgbotik.catalog.service.DatasetImportException;
import com.alfatgbotik.catalog.service.DatasetReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetReaderTest {

    @TempDir
    Path directory;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DatasetReader reader = new DatasetReader(json);

    @Test
    void acceptsFreeTextAndPaidMetadataWithUnknownExternalPermissionsDenied() throws Exception {
        var data = new CatalogTestData(json, directory, "test-v1");

        var result = reader.read(directory);

        assertThat(result.materials()).hasSize(2);
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.materials().getFirst().sourceCost()).isNull();
        assertThat(result.materials()).allMatch(material -> !material.externalMetadataAllowed());
        assertThat(result.chunks()).allMatch(chunk -> !chunk.externalTextAllowed());
        assertThat(result.chunks().getFirst().materialId()).isEqualTo(data.freeId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "paid", "id", "url", "parent", "unknown", "order", "textHash", "boolean"})
    void rejectsBrokenIntegrityRightsAndRelationsWithLocation(String fault) throws Exception {
        var data = new CatalogTestData(json, directory, "test-v1");
        switch (fault) {
            case "hash" -> {
                Files.writeString(directory.resolve("materials.jsonl"), "{}\n");
                assertThatThrownBy(() -> reader.read(directory)).isInstanceOf(DatasetImportException.class)
                        .hasMessageContaining("materials.jsonl").hasMessageContaining("hash mismatch");
                return;
            }
            case "paid" -> data.free.put("sourceCost", 100).put("isPaid", true);
            case "id" -> data.free.put("materialId", UUID.randomUUID().toString());
            case "url" -> data.free.put("url", "http://example.test/unsafe");
            case "parent" -> data.free.withArray("parentMaterialIds").add(data.paidId.toString());
            case "unknown" -> data.free.put("unexpected", true);
            case "order" -> data.chunk.put("order", 2).put("chunkId", DatasetReader.uuid5(
                    UUID.fromString("a1fa0000-0000-4000-8000-000000000002"), data.freeId + ":2").toString());
            case "textHash" -> data.chunk.put("contentHash", "b".repeat(64));
            case "boolean" -> data.free.put("isPaid", "false");
            default -> throw new IllegalStateException();
        }
        data.write();

        assertThatThrownBy(() -> reader.read(directory)).isInstanceOf(DatasetImportException.class)
                .hasMessageContaining(".jsonl");
    }

    @Test
    void rejectsDuplicateChunksAndInvalidUtf8() throws Exception {
        var data = new CatalogTestData(json, directory, "test-v1");
        data.chunks.add(data.chunk.deepCopy());
        data.write();
        assertThatThrownBy(() -> reader.read(directory)).isInstanceOf(DatasetImportException.class)
                .hasMessageContaining("chunks.jsonl:2");
        Files.write(directory.resolve("manifest.json"), new byte[]{(byte) 0xff});
        assertThatThrownBy(() -> reader.read(directory)).isInstanceOf(DatasetImportException.class)
                .hasMessageContaining("UTF-8");
    }
}

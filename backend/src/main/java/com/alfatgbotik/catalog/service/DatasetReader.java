package com.alfatgbotik.catalog.service;

import com.alfatgbotik.catalog.model.CatalogChunk;
import com.alfatgbotik.catalog.model.CatalogDataset;
import com.alfatgbotik.catalog.model.CatalogMaterial;
import com.alfatgbotik.catalog.model.DatasetManifest;
import com.alfatgbotik.catalog.model.MaterialKind;
import com.alfatgbotik.common.api.JsonRequest;
import com.alfatgbotik.common.error.RequestValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Component
public class DatasetReader {

    private static final UUID MATERIAL_NAMESPACE = UUID.fromString("a1fa0000-0000-4000-8000-000000000001");
    private static final UUID CHUNK_NAMESPACE = UUID.fromString("a1fa0000-0000-4000-8000-000000000002");
    private static final Set<String> MANIFEST_FIELDS = Set.of("schemaVersion", "datasetVersion", "createdAt",
            "sourceSnapshot", "materialsCount", "chunksCount", "materialsSha256", "chunksSha256");
    private static final Set<String> MATERIAL_FIELDS = Set.of("schemaVersion", "materialId", "sourceType", "sourceId",
            "title", "description", "url", "isPaid", "sourceCost", "answerAllowed", "externalMetadataAllowed",
            "duration", "topics", "industries", "parentMaterialIds", "sourceUpdatedAt");
    private static final Set<String> CHUNK_FIELDS = Set.of("schemaVersion", "chunkId", "materialId", "order",
            "heading", "text", "contentHash", "externalTextAllowed");

    private final JsonMapper json;

    public DatasetReader(JsonMapper json) {
        this.json = json;
    }

    public CatalogDataset read(Path directory) {
        DatasetManifest manifest = at("manifest.json", () -> manifest(parse(readBytes(directory, "manifest.json"))));
        byte[] materialBytes = readBytes(directory, "materials.jsonl");
        byte[] chunkBytes = readBytes(directory, "chunks.jsonl");
        require(hash(materialBytes).equals(manifest.materialsSha256()), "materials.jsonl", "File hash mismatch");
        require(hash(chunkBytes).equals(manifest.chunksSha256()), "chunks.jsonl", "File hash mismatch");
        List<CatalogMaterial> materials = rows(materialBytes, "materials.jsonl", this::material);
        List<CatalogChunk> chunks = rows(chunkBytes, "chunks.jsonl", this::chunk);
        require(materials.size() == manifest.materialsCount(), "materials.jsonl", "Record count mismatch");
        require(chunks.size() == manifest.chunksCount(), "chunks.jsonl", "Record count mismatch");
        validateRelations(materials, chunks);
        return new CatalogDataset(manifest, List.copyOf(materials), List.copyOf(chunks));
    }

    private DatasetManifest manifest(JsonNode node) {
        JsonRequest request = new JsonRequest(node, MANIFEST_FIELDS);
        schema(request);
        String version = request.requiredText("datasetVersion", 80);
        require(version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "datasetVersion", "Invalid version");
        String source = request.requiredText("sourceSnapshot", 71);
        require(source.matches("sha256:[0-9a-f]{64}"), "sourceSnapshot", "Expected SHA-256 provenance");
        long materials = request.requiredNonNegativeLong("materialsCount");
        long chunks = request.requiredNonNegativeLong("chunksCount");
        require(materials > 0 && materials <= Integer.MAX_VALUE, "materialsCount", "Expected positive count");
        require(chunks > 0 && chunks <= Integer.MAX_VALUE, "chunksCount", "Expected positive count");
        return new DatasetManifest("0.1.0", version, instant(request.requiredText("createdAt", 100)), source,
                (int) materials, (int) chunks, sha(request, "materialsSha256"), sha(request, "chunksSha256"));
    }

    private CatalogMaterial material(JsonNode node) {
        JsonRequest request = new JsonRequest(node, MATERIAL_FIELDS);
        schema(request);
        UUID id = uuid(request.requiredText("materialId", 36));
        UUID source = uuid(request.requiredText("sourceId", 36));
        MaterialKind kind = request.nullableEnum("sourceType", MaterialKind.class);
        require(kind != null, "sourceType", "Field is required");
        require(id.equals(uuid5(MATERIAL_NAMESPACE, kind.name() + ":" + source)), "materialId", "UUIDv5 mismatch");
        boolean paid = bool(node, "isPaid");
        boolean answerAllowed = bool(node, "answerAllowed");
        require(!paid || !answerAllowed, "answerAllowed", "Paid text is forbidden");
        Long cost = null;
        if (kind == MaterialKind.COURSE || node.has("sourceCost")) {
            require(node.has("sourceCost"), "sourceCost", "Field is required for a course");
            if (!node.get("sourceCost").isNull()) {
                cost = request.requiredNonNegativeLong("sourceCost");
            }
            require(paid == (cost != null && cost > 0), "isPaid", "Does not match sourceCost");
        }
        String url = request.requiredText("url", 2000);
        try {
            var uri = java.net.URI.create(url);
            require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null,
                    "url", "Expected an absolute HTTPS URL without credentials");
        } catch (IllegalArgumentException exception) {
            throw new DatasetImportException("url", "Invalid URL");
        }
        require(node.has("description") && node.has("duration") && node.has("sourceUpdatedAt"),
                "material", "Nullable fields must be present");
        String updatedAt = request.nullableText("sourceUpdatedAt", 100);
        return new CatalogMaterial(id, kind, source, request.requiredText("title", 250),
                request.nullableText("description", 1000), url, paid, cost, answerAllowed,
                bool(node, "externalMetadataAllowed"), request.nullableText("duration", 100),
                strings(node, "topics"), strings(node, "industries"),
                strings(node, "parentMaterialIds").stream().map(this::uuid).toList(),
                updatedAt == null ? null : instant(updatedAt));
    }

    private CatalogChunk chunk(JsonNode node) {
        JsonRequest request = new JsonRequest(node, CHUNK_FIELDS);
        schema(request);
        UUID id = uuid(request.requiredText("chunkId", 36));
        UUID material = uuid(request.requiredText("materialId", 36));
        long order = request.requiredNonNegativeLong("order");
        require(order > 0 && order <= Integer.MAX_VALUE, "order", "Expected a positive integer");
        require(id.equals(uuid5(CHUNK_NAMESPACE, material + ":" + order)), "chunkId", "UUIDv5 mismatch");
        String text = request.requiredText("text", 6000);
        require(text.codePointCount(0, text.length()) >= 30, "text", "Must contain at least 30 characters");
        String contentHash = sha(request, "contentHash");
        require(contentHash.equals(hash(text.getBytes(StandardCharsets.UTF_8))), "contentHash", "Text hash mismatch");
        require(node.has("heading"), "heading", "Nullable field must be present");
        return new CatalogChunk(id, material, (int) order, request.nullableText("heading", 250), text,
                contentHash, bool(node, "externalTextAllowed"));
    }

    private void validateRelations(List<CatalogMaterial> materials, List<CatalogChunk> chunks) {
        Map<UUID, CatalogMaterial> lookup = new HashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            CatalogMaterial material = materials.get(i);
            require(lookup.putIfAbsent(material.materialId(), material) == null,
                    "materials.jsonl:" + (i + 1), "Duplicate material ID");
        }
        for (int i = 0; i < materials.size(); i++) {
            CatalogMaterial material = materials.get(i);
            for (UUID parentId : material.parentMaterialIds()) {
                CatalogMaterial parent = lookup.get(parentId);
                require(parent != null && parent.sourceType() == MaterialKind.COURSE
                                && !parentId.equals(material.materialId()), "materials.jsonl:" + (i + 1),
                        "Parent must reference a course from this dataset");
                require(!material.answerAllowed() || !parent.isPaid(), "materials.jsonl:" + (i + 1),
                        "Answer text references a paid parent");
            }
        }
        Set<UUID> ids = new HashSet<>();
        Map<UUID, Set<Integer>> orders = new HashMap<>();
        Map<UUID, Integer> chunkLines = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            CatalogChunk chunk = chunks.get(i);
            String location = "chunks.jsonl:" + (i + 1);
            require(ids.add(chunk.chunkId()), location, "Duplicate chunk ID");
            CatalogMaterial material = lookup.get(chunk.materialId());
            require(material != null && material.answerAllowed() && !material.isPaid(), location,
                    "Chunk must belong to an allowed free material");
            require(orders.computeIfAbsent(chunk.materialId(), key -> new HashSet<>()).add(chunk.order()),
                    location, "Duplicate chunk order");
            chunkLines.put(chunk.materialId(), i + 1);
        }
        for (int i = 0; i < materials.size(); i++) {
            CatalogMaterial material = materials.get(i);
            Set<Integer> positions = orders.getOrDefault(material.materialId(), Set.of());
            require(material.answerAllowed() == !positions.isEmpty(), "materials.jsonl:" + (i + 1),
                    "answerAllowed does not match chunks");
            for (int position = 1; position <= positions.size(); position++) {
                require(positions.contains(position), "chunks.jsonl:" + chunkLines.get(material.materialId()),
                        "Gap in chunk order");
            }
        }
    }

    private <T> List<T> rows(byte[] bytes, String filename, Function<JsonNode, T> convert) {
        List<T> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(decode(bytes, filename)))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                String location = filename + ":" + (++number);
                String row = line;
                require(!row.isBlank(), location, "Empty JSONL line");
                result.add(at(location, () -> convert.apply(json.readTree(row))));
            }
        } catch (IOException exception) {
            throw new DatasetImportException(filename, "Could not read JSONL");
        }
        return result;
    }

    private <T> T at(String location, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DatasetImportException exception) {
            throw new DatasetImportException(location, exception.getMessage());
        } catch (RequestValidationException exception) {
            var error = exception.getErrors().getFirst();
            throw new DatasetImportException(location + ":" + error.field(), error.message());
        } catch (JacksonException exception) {
            throw new DatasetImportException(location, "Invalid JSON");
        }
    }

    private JsonNode parse(byte[] bytes) {
        return json.readTree(decode(bytes, "manifest.json"));
    }

    private byte[] readBytes(Path directory, String filename) {
        try {
            return Files.readAllBytes(directory.resolve(filename));
        } catch (IOException exception) {
            throw new DatasetImportException(filename, "File is missing or unreadable");
        }
    }

    private String decode(byte[] bytes, String filename) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            require(!text.startsWith("\uFEFF"), filename, "UTF-8 BOM is forbidden");
            return text;
        } catch (CharacterCodingException exception) {
            throw new DatasetImportException(filename, "Expected valid UTF-8");
        }
    }

    private void schema(JsonRequest request) {
        require("0.1.0".equals(request.requiredText("schemaVersion", 20)), "schemaVersion", "Unsupported version");
    }

    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isBoolean(), field, "Expected a boolean");
        return value.booleanValue();
    }

    private List<String> strings(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null) {
            return List.of();
        }
        require(array.isArray(), field, "Expected an array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            require(item.isString() && !item.stringValue().isBlank(), field, "Expected nonblank strings");
            result.add(item.stringValue());
        }
        require(new HashSet<>(result).size() == result.size(), field, "Duplicate array value");
        return List.copyOf(result);
    }

    private String sha(JsonRequest request, String field) {
        String value = request.requiredText(field, 64);
        require(value.matches("[0-9a-f]{64}"), field, "Expected lowercase SHA-256");
        return value;
    }

    private UUID uuid(String value) {
        try {
            require(value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"), "id", "Invalid UUID");
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new DatasetImportException("id", "Invalid UUID");
        }
    }

    private Instant instant(String value) {
        try {
            require(value.endsWith("Z"), "timestamp", "Expected UTC timestamp ending in Z");
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new DatasetImportException("timestamp", "Invalid UTC timestamp");
        }
    }

    public static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 15) | 80);
            bytes[8] = (byte) ((bytes[8] & 63) | 128);
            ByteBuffer value = ByteBuffer.wrap(bytes);
            return new UUID(value.getLong(), value.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable");
        }
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private void require(boolean condition, String location, String reason) {
        if (!condition) {
            throw new DatasetImportException(location, reason);
        }
    }
}

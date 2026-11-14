package com.alfatgbotik.catalog;

import com.alfatgbotik.assistant.integration.CatalogEmbeddings;
import com.alfatgbotik.assistant.retrieval.CatalogSearchService;
import com.alfatgbotik.catalog.model.EmbeddingModelInfo;
import com.alfatgbotik.catalog.persistence.CatalogRepository;
import com.alfatgbotik.catalog.service.CatalogImportService;
import com.alfatgbotik.catalog.service.DatasetImportException;
import com.alfatgbotik.catalog.service.KnowledgeUnavailableException;
import com.alfatgbotik.catalog.service.MaterialNotFoundException;
import com.alfatgbotik.catalog.service.MaterialService;
import com.alfatgbotik.common.error.RequestIdFilter;
import com.alfatgbotik.identity.service.SessionService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = "telegram.enabled=false")
class CatalogIntegrationTest {

    private static final String DATABASE = "stage5_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PG = "jdbc:postgresql://localhost:" + System.getenv().getOrDefault("DB_PORT", "5432") + "/";
    private static final EmbeddingModelInfo MODEL = new EmbeddingModelInfo("embeddinggemma:300m",
            "sha256:" + "a".repeat(64), 768);

    @TempDir
    Path directory;
    @Autowired
    CatalogImportService importer;
    @Autowired
    MaterialService materials;
    @Autowired
    CatalogSearchService search;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionService sessions;
    @Autowired
    RequestIdFilter requestIds;
    @Autowired
    WebApplicationContext context;
    @MockitoBean
    CatalogEmbeddings embeddings;
    @MockitoSpyBean
    CatalogRepository catalog;

    private MockMvc mvc;
    private String token;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        properties.add("spring.datasource.url", () -> PG + DATABASE + "?currentSchema=app,knowledge,public");
    }

    @AfterAll
    static void dropTestDatabase(@Autowired DataSource datasource) throws Exception {
        datasource.unwrap(HikariDataSource.class).close();
        if (!DATABASE.matches("stage5_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test database name");
        }
        try (var connection = DriverManager.getConnection(PG + "postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + DATABASE + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void configureEmbeddings() {
        reset(embeddings, catalog);
        when(embeddings.describe()).thenReturn(MODEL);
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> {
                float[] vector = new float[768];
                vector[input.toLowerCase(Locale.ROOT).contains("free") ? 0 : 1] = 1;
                return vector;
            }).toList();
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIds).apply(springSecurity()).build();
        token = sessions.issue(UUID.randomUUID()).accessToken();
    }

    @AfterEach
    void revokeTestSession() {
        sessions.find(token).ifPresent(sessions::revoke);
    }

    @Test
    void importsOnceAndExposesOnlyContractCardThroughProtectedApi() throws Exception {
        var data = dataset();
        var first = importer.importDataset(data.directory);
        var repeated = importer.importDataset(data.directory);

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.vectorCount()).isEqualTo(3);
        verify(embeddings).embed(anyList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge.search_vectors WHERE index_id = ?",
                Integer.class, first.id())).isEqualTo(3);
        assertThat(mvc.perform(get("/api/v1/materials/" + data.freeId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        var response = mvc.perform(get("/api/v1/materials/" + data.paidId)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var card = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(card.propertyNames()).containsExactlyInAnyOrder("id", "title", "kind", "description",
                "recommendationReason", "isPaid", "duration", "url");
        assertThat(card.get("isPaid").booleanValue()).isTrue();
        assertThat(card.get("recommendationReason").isNull()).isTrue();
        assertThat(mvc.perform(get("/api/v1/materials/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/materials/not-a-uuid")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void rejectsChangedFilesUnderExistingVersionBeforeEmbedding() throws Exception {
        var data = dataset();
        var original = importer.importDataset(data.directory);
        data.free.put("title", "Changed free title");
        data.write();
        clearInvocations(embeddings);

        assertThatThrownBy(() -> importer.importDataset(data.directory)).isInstanceOf(DatasetImportException.class);

        verify(embeddings, never()).embed(anyList());
        assertThat(catalog.activeIndex().orElseThrow().id()).isEqualTo(original.id());
        assertThat(materials.getById(data.freeId).title()).isEqualTo("Free material");
    }

    @Test
    void modelFailureLeavesPreviousCatalogActive() throws Exception {
        var first = dataset();
        var original = importer.importDataset(first.directory);
        var second = dataset();
        when(embeddings.embed(anyList())).thenThrow(new KnowledgeUnavailableException("simulated model outage"));

        assertThatThrownBy(() -> importer.importDataset(second.directory)).isInstanceOf(KnowledgeUnavailableException.class);

        assertThat(catalog.findDataset(second.version)).isEmpty();
        assertThat(catalog.activeIndex().orElseThrow().id()).isEqualTo(original.id());
        assertThat(materials.getById(first.freeId)).isNotNull();
    }

    @Test
    void activationFailureRollsBackAllDatasetRowsAndActivePointer() throws Exception {
        var first = dataset();
        var original = importer.importDataset(first.directory);
        var second = dataset();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("simulated final database failure");
        }).when(catalog).activate(any(UUID.class));

        assertThatThrownBy(() -> importer.importDataset(second.directory))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(catalog.findDataset(second.version)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge.materials WHERE dataset_version = ?",
                Integer.class, second.version)).isZero();
        assertThat(catalog.activeIndex().orElseThrow().id()).isEqualTo(original.id());
    }

    @Test
    void replacementRemovesOldSourcesFromActiveSearchAndUsesPgvectorRanking() throws Exception {
        var first = dataset();
        importer.importDataset(first.directory);
        var second = dataset();
        var active = importer.importDataset(second.directory);

        assertThatThrownBy(() -> materials.getById(first.freeId)).isInstanceOf(MaterialNotFoundException.class);
        var result = search.search("free business question", 5, 3, 0.35);

        assertThat(result.index().id()).isEqualTo(active.id());
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().materialId()).isEqualTo(second.freeId);
        assertThat(result.chunks().getFirst().score()).isEqualTo(1);
        assertThat(result.materials()).hasSize(1);
        assertThat(result.materials().getFirst().chunkId()).isNull();
    }

    @Test
    void changedModelRequiresNewIndexAndInvalidVectorsAreRejected() throws Exception {
        var data = dataset();
        var original = importer.importDataset(data.directory);
        var changed = new EmbeddingModelInfo(MODEL.name(), "sha256:" + "b".repeat(64), 768);
        when(embeddings.describe()).thenReturn(changed);
        clearInvocations(embeddings);

        assertThatThrownBy(() -> search.search("free question", 5, 3, 0.35))
                .isInstanceOf(KnowledgeUnavailableException.class);
        verify(embeddings, never()).embed(anyList());
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[3], new float[3], new float[3]));
        assertThatThrownBy(() -> importer.importDataset(data.directory)).isInstanceOf(KnowledgeUnavailableException.class);
        assertThat(catalog.activeIndex().orElseThrow().id()).isEqualTo(original.id());
    }

    private CatalogTestData dataset() throws Exception {
        String version = "test-" + UUID.randomUUID();
        return new CatalogTestData(json, directory.resolve(version), version);
    }
}

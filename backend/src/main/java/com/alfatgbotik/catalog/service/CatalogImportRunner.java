package com.alfatgbotik.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Order(-1)
@ConditionalOnProperty(prefix = "catalog", name = "import-path")
public class CatalogImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportRunner.class);
    private final CatalogImportService importer;
    private final Path path;
    private final DatasetReader reader;

    public CatalogImportRunner(CatalogImportService importer, DatasetReader reader,
                               @Value("${catalog.import-path}") String path) {
        this.importer = importer;
        this.reader = reader;
        this.path = Path.of(path);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (arguments.containsOption("catalog.validate-only")) {
            var dataset = reader.read(path);
            log.info("Catalog files validated (dataset={}, materials={}, chunks={})",
                    dataset.manifest().datasetVersion(), dataset.materials().size(), dataset.chunks().size());
            return;
        }
        var index = importer.importDataset(path);
        log.info("Catalog import completed (dataset={}, index={}, vectors={}, model={})", index.datasetVersion(),
                index.id(), index.vectorCount(), index.model().name());
    }
}

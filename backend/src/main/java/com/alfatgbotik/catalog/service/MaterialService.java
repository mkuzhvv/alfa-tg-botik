package com.alfatgbotik.catalog.service;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.persistence.CatalogRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Set;

@Service
public class MaterialService {

    private final CatalogRepository catalog;

    public MaterialService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public MaterialCardDto getById(UUID materialId) {
        return catalog.findMaterial(materialId).orElseThrow(MaterialNotFoundException::new);
    }

    public String requireActiveMaterials(Set<UUID> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            throw new MaterialNotFoundException();
        }
        String version = catalog.activeIndex().orElseThrow(MaterialNotFoundException::new).datasetVersion();
        if (catalog.findMaterialIds(version, materialIds).size() != materialIds.size()) {
            throw new MaterialNotFoundException();
        }
        return version;
    }
}

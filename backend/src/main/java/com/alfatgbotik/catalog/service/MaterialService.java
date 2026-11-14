package com.alfatgbotik.catalog.service;

import com.alfatgbotik.catalog.api.MaterialCardDto;
import com.alfatgbotik.catalog.persistence.CatalogRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MaterialService {

    private final CatalogRepository catalog;

    public MaterialService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public MaterialCardDto getById(UUID materialId) {
        return catalog.findMaterial(materialId).orElseThrow(MaterialNotFoundException::new);
    }
}

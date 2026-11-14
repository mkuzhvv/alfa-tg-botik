package com.alfatgbotik.catalog.api;

import com.alfatgbotik.catalog.service.MaterialService;
import com.alfatgbotik.common.error.RequestValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/materials")
public class MaterialController {

    private final MaterialService materials;

    public MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    @GetMapping("/{materialId}")
    public MaterialCardDto getMaterial(@PathVariable String materialId) {
        if (!materialId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw RequestValidationException.malformed("materialId", "Expected a UUID");
        }
        return materials.getById(UUID.fromString(materialId));
    }
}

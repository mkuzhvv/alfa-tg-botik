package com.alfatgbotik.catalog.service;

public class MaterialNotFoundException extends RuntimeException {

    public MaterialNotFoundException() {
        super("Material not found");
    }
}

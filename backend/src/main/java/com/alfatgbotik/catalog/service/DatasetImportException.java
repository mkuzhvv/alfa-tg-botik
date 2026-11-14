package com.alfatgbotik.catalog.service;

public class DatasetImportException extends RuntimeException {

    public DatasetImportException(String location, String reason) {
        super(location + ": " + reason);
    }
}

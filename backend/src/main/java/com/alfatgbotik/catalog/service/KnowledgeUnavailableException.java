package com.alfatgbotik.catalog.service;

public class KnowledgeUnavailableException extends RuntimeException {

    public KnowledgeUnavailableException(String reason) {
        super(reason);
    }
}

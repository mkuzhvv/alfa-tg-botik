package com.alfatgbotik.assistant.service;

public class AssistantFailure extends RuntimeException {

    private final String code;

    public AssistantFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

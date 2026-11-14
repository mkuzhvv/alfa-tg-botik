package com.alfatgbotik.identity.api;

import com.alfatgbotik.common.api.JsonRequest;
import tools.jackson.databind.JsonNode;

import java.util.Set;

public record TelegramLoginRequest(String initData) {

    public static TelegramLoginRequest from(JsonNode body) {
        return new TelegramLoginRequest(new JsonRequest(body, Set.of("initData")).requiredText("initData", 16384));
    }
}

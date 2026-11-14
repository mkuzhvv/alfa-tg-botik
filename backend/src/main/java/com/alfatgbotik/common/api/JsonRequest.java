package com.alfatgbotik.common.api;

import com.alfatgbotik.common.error.RequestValidationException;
import tools.jackson.databind.JsonNode;

import java.util.Set;

public final class JsonRequest {

    private final JsonNode body;

    public JsonRequest(JsonNode body, Set<String> allowedFields) {
        if (body == null || !body.isObject()) {
            throw RequestValidationException.malformed("request", "Expected a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!allowedFields.contains(field)) {
                throw RequestValidationException.malformed(field, "Unknown field");
            }
        }
        this.body = body;
    }

    public boolean contains(String field) {
        return body.has(field);
    }

    public String requiredText(String field, int maxLength) {
        String value = nullableText(field, maxLength);
        if (value == null) {
            throw RequestValidationException.invalid(field, "Field is required");
        }
        return value;
    }

    public String nullableText(String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw RequestValidationException.malformed(field, "Expected a string or null");
        }
        String text = value.stringValue();
        if (text.isBlank() || text.codePointCount(0, text.length()) > maxLength) {
            throw RequestValidationException.invalid(field, "Must contain 1 to " + maxLength + " characters");
        }
        return text;
    }

    public long requiredNonNegativeLong(String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            throw RequestValidationException.invalid(field, "Field is required");
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw RequestValidationException.malformed(field, "Expected an integer within the 64-bit range");
        }
        if (value.longValue() < 0) {
            throw RequestValidationException.invalid(field, "Must not be negative");
        }
        return value.longValue();
    }

    public <T extends Enum<T>> T nullableEnum(String field, Class<T> type) {
        String value = nullableText(field, 100);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw RequestValidationException.invalid(field, "Unsupported value");
        }
    }
}

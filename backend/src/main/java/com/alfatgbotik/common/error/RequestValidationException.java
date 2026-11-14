package com.alfatgbotik.common.error;

import java.util.List;

public class RequestValidationException extends RuntimeException {

    private final int status;
    private final List<FieldError> errors;

    private RequestValidationException(int status, String field, String code, String message) {
        super(message);
        this.status = status;
        this.errors = List.of(new FieldError(field.length() <= 200 ? field : "request", code, message));
    }

    public static RequestValidationException malformed(String field, String message) {
        return new RequestValidationException(400, field, "INVALID_FORMAT", message);
    }

    public static RequestValidationException invalid(String field, String message) {
        return new RequestValidationException(422, field, "INVALID_VALUE", message);
    }

    public int getStatus() {
        return status;
    }

    public List<FieldError> getErrors() {
        return errors;
    }
}

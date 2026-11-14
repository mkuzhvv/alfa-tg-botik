package com.alfatgbotik.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

public record ApiProblem(String type, String title, int status, String code, UUID requestId, String instance,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> errors) {
}

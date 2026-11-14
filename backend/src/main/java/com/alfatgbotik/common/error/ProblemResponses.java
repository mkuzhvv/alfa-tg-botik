package com.alfatgbotik.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ProblemResponses {

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    public ResponseEntity<ApiProblem> response(HttpServletRequest request, int status, String code,
                                               List<FieldError> errors) {
        ApiProblem problem = create(request, status, code, errors);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("X-Request-Id", problem.requestId().toString()).body(problem);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        ApiProblem problem = create(request, status, code, List.of());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Request-Id", problem.requestId().toString());
        json.writeValue(response.getOutputStream(), problem);
    }

    private ApiProblem create(HttpServletRequest request, int status, String code, List<FieldError> errors) {
        return new ApiProblem("about:blank", HttpStatus.valueOf(status).getReasonPhrase(), status, code,
                RequestIdFilter.getRequestId(request), request.getRequestURI(), errors);
    }
}

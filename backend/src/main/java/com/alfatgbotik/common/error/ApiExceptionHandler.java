package com.alfatgbotik.common.error;

import com.alfatgbotik.identity.service.TelegramAuthUnavailableException;
import com.alfatgbotik.catalog.service.MaterialNotFoundException;
import com.alfatgbotik.catalog.service.KnowledgeUnavailableException;
import com.alfatgbotik.identity.service.ProfileVersionConflictException;
import com.alfatgbotik.identity.service.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ProblemResponses problems;

    public ApiExceptionHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiProblem> validation(RequestValidationException exception, HttpServletRequest request) {
        return problems.response(request, exception.getStatus(),
                exception.getStatus() == 400 ? "MALFORMED_REQUEST" : "VALIDATION_FAILED", exception.getErrors());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiProblem> telegramAuthentication(HttpServletRequest request) {
        return problems.response(request, 401, "TELEGRAM_AUTH_INVALID", List.of());
    }

    @ExceptionHandler(ProfileVersionConflictException.class)
    public ResponseEntity<ApiProblem> versionConflict(HttpServletRequest request) {
        return problems.response(request, 409, "VERSION_CONFLICT", List.of());
    }

    @ExceptionHandler({UserNotFoundException.class, MaterialNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiProblem> notFound(HttpServletRequest request) {
        return problems.response(request, 404, "NOT_FOUND", List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> malformedJson(HttpServletRequest request) {
        return problems.response(request, 400, "MALFORMED_REQUEST", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiProblem> unsupportedMethod(HttpRequestMethodNotSupportedException exception,
                                                       HttpServletRequest request) {
        ResponseEntity<ApiProblem> response = problems.response(request, 405, "MALFORMED_REQUEST", List.of());
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported == null) {
            return response;
        }
        return ResponseEntity.status(405).headers(response.getHeaders())
                .allow(supported.toArray(HttpMethod[]::new))
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiProblem> unsupportedContentType(HttpServletRequest request) {
        return problems.response(request, 415, "MALFORMED_REQUEST", List.of());
    }

    @ExceptionHandler({TelegramAuthUnavailableException.class, KnowledgeUnavailableException.class})
    public ResponseEntity<ApiProblem> authUnavailable(HttpServletRequest request) {
        return problems.response(request, 503, "SERVICE_UNAVAILABLE", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> unexpected(Exception exception, HttpServletRequest request) {
        log.error("HTTP request failed (requestId={}, type={})", RequestIdFilter.getRequestId(request),
                exception.getClass().getSimpleName());
        return problems.response(request, 503, "SERVICE_UNAVAILABLE", List.of());
    }
}

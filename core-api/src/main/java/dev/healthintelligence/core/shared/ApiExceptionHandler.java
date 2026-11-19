package dev.healthintelligence.core.shared;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into RFC 9457 Problem Details with a stable {@code code} and a
 * {@code correlationId} (ADR 0009). Submitted values are never echoed back.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Bean Validation constraint name to stable field error code. */
    private static final Map<String, String> CONSTRAINT_CODES = Map.of(
            "NotNull", "REQUIRED",
            "NotBlank", "REQUIRED",
            "NotEmpty", "REQUIRED",
            "Size", "INVALID_LENGTH",
            "Past", "PAST_DATE_REQUIRED",
            "DecimalMin", "OUT_OF_RANGE",
            "DecimalMax", "OUT_OF_RANGE",
            "Min", "OUT_OF_RANGE",
            "Max", "OUT_OF_RANGE",
            "Pattern", "INVALID_FORMAT");

    private final CorrelationIds correlationIds;

    ApiExceptionHandler(CorrelationIds correlationIds) {
        this.correlationIds = correlationIds;
    }

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException exception) {
        return ResponseEntity.status(exception.status())
                .body(Problems.of(exception.status(), exception.code(), exception.title(),
                        correlationIds.current(), exception.errors()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        String correlationId = correlationIds.current();
        log.error("Unhandled error, correlationId={}", correlationId, exception);
        return ResponseEntity.internalServerError()
                .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                        correlationId, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> {
                    String code = CONSTRAINT_CODES.getOrDefault(error.getCode(), "INVALID");
                    return new FieldViolation(error.getField(), code, error.getField() + " is invalid (" + code + ")");
                })
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Malformed JSON, unknown properties, wrong types or unknown enum values. The parser message
        // may quote the submitted value, so it is not returned.
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body", List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            org.springframework.web.multipart.MaxUploadSizeExceededException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Document larger than 10 MB", List.of());
    }

    /** Spring MVC's own exceptions (405, 415, ...) keep their status and get our extensions. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = HttpStatus.resolve(status.value()) instanceof HttpStatus known ? known.name() : "HTTP_ERROR";
        String title = HttpStatus.resolve(status.value()) instanceof HttpStatus known ? known.getReasonPhrase() : "Error";
        return ResponseEntity.status(status).headers(headers)
                .body(Problems.of(status, code, title, correlationIds.current(), List.of()));
    }

    private ResponseEntity<Object> problem(HttpStatus status, String code, String title, List<FieldViolation> errors) {
        return ResponseEntity.status(status)
                .body(Problems.of(status, code, title, correlationIds.current(), errors));
    }
}

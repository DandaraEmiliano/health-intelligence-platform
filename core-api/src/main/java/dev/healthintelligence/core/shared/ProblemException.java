package dev.healthintelligence.core.shared;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * An expected failure that maps to an RFC 9457 response with a stable {@code code} (ADR 0009).
 * Titles are generic and never contain health data.
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldViolation> errors;
    private final Long retryAfterSeconds;

    public ProblemException(HttpStatus status, String code, String title, List<FieldViolation> errors) {
        this(status, code, title, errors, null);
    }

    private ProblemException(HttpStatus status, String code, String title, List<FieldViolation> errors,
                             Long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = List.copyOf(errors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 429 or 503 with a {@code Retry-After} header (ADR 0009). */
    public static ProblemException retryLater(HttpStatus status, String code, String title, long seconds) {
        return new ProblemException(status, code, title, List.of(), Math.max(1, seconds));
    }

    public ProblemException(HttpStatus status, String code, String title) {
        this(status, code, title, List.of());
    }

    public static ProblemException validation(List<FieldViolation> errors) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", errors);
    }

    /** Also used for resources of another patient: 404, so their existence is not revealed. */
    public static ProblemException notFound(String code) {
        return new ProblemException(HttpStatus.NOT_FOUND, code, "Not found");
    }

    public static ProblemException conflict(String code) {
        return new ProblemException(HttpStatus.CONFLICT, code, "Conflict");
    }

    public static ProblemException forbidden(String code) {
        return new ProblemException(HttpStatus.FORBIDDEN, code, "Forbidden");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public List<FieldViolation> errors() {
        return errors;
    }

    /** Seconds for the {@code Retry-After} header, or null. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

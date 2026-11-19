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

    public ProblemException(HttpStatus status, String code, String title, List<FieldViolation> errors) {
        super(code);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = List.copyOf(errors);
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
}

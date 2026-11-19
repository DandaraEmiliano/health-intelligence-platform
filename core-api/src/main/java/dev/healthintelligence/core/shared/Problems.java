package dev.healthintelligence.core.shared;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the Problem Details body every error shares (ADR 0009). */
public final class Problems {

    static final String TYPE_BASE = "https://docs.health-intelligence-platform.dev/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String correlationId,
                                   List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}

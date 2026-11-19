package dev.healthintelligence.core.shared;

import org.springframework.http.HttpStatus;

/** Strong ETags from the entity version, and the If-Match check for updates (ADR 0009). */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Fails with 428 when {@code If-Match} is missing and 412 when it does not match the current
     * version. A list of ETags is accepted, as HTTP allows.
     */
    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ProblemException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "If-Match header is required");
        }
        String current = of(currentVersion);
        for (String candidate : ifMatch.split(",")) {
            if (candidate.strip().equals(current)) {
                return;
            }
        }
        throw new ProblemException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                "Resource was modified; reload and retry");
    }
}

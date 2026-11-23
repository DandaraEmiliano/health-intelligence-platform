package dev.healthintelligence.core.assistant;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;

/** Opaque Base64URL cursor over (timestamp, id) (ADR 0009). */
record Cursor(Instant at, UUID id) {

    String encode() {
        String raw = at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new Cursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException invalid) {
            throw ProblemException.validation(List.of(new FieldViolation("cursor", "INVALID_FORMAT", "cursor is not valid")));
        }
    }
}

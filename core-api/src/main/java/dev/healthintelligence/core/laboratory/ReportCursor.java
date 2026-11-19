package dev.healthintelligence.core.laboratory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;

/** Opaque cursor over (collection date or 0001-01-01, id), newest first. */
record ReportCursor(LocalDate collectedAt, UUID id) {

    static final LocalDate NO_DATE = LocalDate.of(1, 1, 1);

    static ReportCursor after(LaboratoryReport report) {
        return new ReportCursor(report.collectedAt() == null ? NO_DATE : report.collectedAt(), report.id());
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((collectedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static ReportCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new ReportCursor(LocalDate.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException invalid) {
            throw ProblemException.validation(List.of(new FieldViolation("cursor", "INVALID_FORMAT", "cursor is not valid")));
        }
    }
}

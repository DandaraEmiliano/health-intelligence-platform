package dev.healthintelligence.core.timeline;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.documents.DocumentTimeline;
import dev.healthintelligence.core.laboratory.VerifiedResults;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's health history, newest first: finished laboratory reviews (by collection date)
 * and uploaded documents (by upload date). Both sources are paged by the same keyset cursor.
 */
@RestController
class TimelineController {

    private static final Set<String> TYPES = Set.of("LABORATORY_REPORT", "DOCUMENT");

    private final CurrentPatient currentPatient;
    private final VerifiedResults verified;
    private final DocumentTimeline documents;

    TimelineController(CurrentPatient currentPatient, VerifiedResults verified, DocumentTimeline documents) {
        this.currentPatient = currentPatient;
        this.verified = verified;
        this.documents = documents;
    }

    @GetMapping("/api/v1/health-timeline")
    Page getHealthTimeline(@RequestParam(name = "types", required = false) List<String> types,
                           @RequestParam(name = "from", required = false) LocalDate from,
                           @RequestParam(name = "to", required = false) LocalDate to,
                           @RequestParam(name = "cursor", required = false) String cursor,
                           @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? 20 : limit;
        List<FieldViolation> errors = new ArrayList<>();
        if (size < 1 || size > 100) {
            errors.add(new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100"));
        }
        Set<String> wanted = types == null || types.isEmpty() ? TYPES : Set.copyOf(types);
        if (!TYPES.containsAll(wanted)) {
            errors.add(new FieldViolation("types", "INVALID_VALUE", "types are LABORATORY_REPORT and DOCUMENT"));
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(new FieldViolation("from", "INVALID_PERIOD", "from must not be after to"));
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
        Cursor after = Cursor.decode(cursor);
        PatientId patient = currentPatient.scope().patient();

        List<Item> merged = new ArrayList<>();
        if (wanted.contains("LABORATORY_REPORT")) {
            verified.reports(patient, after == null ? null : after.date(), after == null ? null : after.id(), from, to,
                    size + 1).forEach(r ->
                    merged.add(new Item("LABORATORY_REPORT", r.id(), r.collectedAt(),
                            r.laboratoryName() == null ? "Laudo laboratorial" : r.laboratoryName(), r.status(),
                            r.resultCount(), r.outOfRangeCount())));
        }
        if (wanted.contains("DOCUMENT")) {
            // Documents are keyed by upload instant, the cursor by day: fetch up to the end of the cursor's
            // day and let the in-memory keyset below drop what was already returned.
            Instant before = after == null ? null : after.date().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            documents.page(patient, before, null, size + 1 + (after == null ? 0 : 50)).stream()
                    .filter(d -> (from == null || !d.uploadedOn().isBefore(from)) && (to == null || !d.uploadedOn().isAfter(to)))
                    .forEach(d -> merged.add(new Item("DOCUMENT", d.id(), d.uploadedOn(), d.filename(), d.status(), null, null)));
        }
        Comparator<Item> newestFirst = Comparator.comparing(Item::occurredOn).thenComparing(Item::id).reversed();
        List<Item> ordered = merged.stream()
                .filter(item -> after == null || newestFirst.compare(item, new Item(null, after.id(), after.date(), null, null, null, null)) > 0)
                .sorted(newestFirst)
                .toList();
        boolean more = ordered.size() > size;
        List<Item> items = more ? ordered.subList(0, size) : ordered;
        String next = more ? new Cursor(items.getLast().occurredOn(), items.getLast().id()).encode() : null;
        return new Page(items, next);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Item(String type, UUID id, LocalDate occurredOn, String title, String status, Integer resultCount,
                Integer outOfRangeCount) {
    }

    record Page(List<Item> items, String nextCursor) {
    }

    /** (occurredOn, id) of the last item, newest-first order. */
    record Cursor(LocalDate date, UUID id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((date + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                return new Cursor(LocalDate.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
            } catch (RuntimeException invalid) {
                throw ProblemException.validation(List.of(new FieldViolation("cursor", "INVALID_FORMAT", "cursor is not valid")));
            }
        }
    }
}

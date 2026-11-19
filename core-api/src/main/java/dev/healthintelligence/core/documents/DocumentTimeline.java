package dev.healthintelligence.core.documents;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A patient's live documents for the timeline, newest upload first. */
@Component
public class DocumentTimeline {

    public record Entry(UUID id, LocalDate uploadedOn, Instant uploadedAt, String filename, String status) {
    }

    private final DocumentRepository documents;

    DocumentTimeline(DocumentRepository documents) {
        this.documents = documents;
    }

    /** Documents uploaded before the given instant (exclusive, with the id as tie-breaker). */
    @Transactional(readOnly = true)
    public List<Entry> page(PatientId patient, Instant before, UUID beforeId, int limit) {
        return documents.page(patient, null, before, beforeId, Limit.of(limit)).stream()
                .map(d -> new Entry(d.id(), d.uploadedAt().atOffset(ZoneOffset.UTC).toLocalDate(), d.uploadedAt(),
                        d.originalFilename(), d.status().name()))
                .toList();
    }
}

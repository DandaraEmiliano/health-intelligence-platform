package dev.healthintelligence.core.notifications;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.laboratory.ExtractionResultStored;
import dev.healthintelligence.core.patient.PatientDirectory;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a stored extraction result into a notification, in the ingestion transaction: either both
 * are stored or neither is. {@code dedupe_key} keeps one notification per document and outcome.
 */
@Component
class ExtractionNotifier {

    private final NotificationRepository notifications;
    private final PatientDirectory patients;
    private final JsonMapper json;
    private final Clock clock;

    ExtractionNotifier(NotificationRepository notifications, PatientDirectory patients, JsonMapper json, Clock clock) {
        this.notifications = notifications;
        this.patients = patients;
        this.json = json;
        this.clock = clock;
    }

    @EventListener
    void on(ExtractionResultStored event) {
        UserId owner = patients.ownerOf(event.patient()).orElse(null);
        if (owner == null) {
            return; // account purged meanwhile
        }
        Notification.Type type = switch (event.outcome()) {
            case "SUCCEEDED" -> event.reportId() == null ? Notification.Type.DOCUMENT_PROCESSED : Notification.Type.REVIEW_PENDING;
            case "FAILED" -> Notification.Type.EXTRACTION_FAILED;
            case "UNSUPPORTED_SCANNED" -> Notification.Type.DOCUMENT_UNSUPPORTED;
            default -> throw new IllegalArgumentException("unknown outcome " + event.outcome());
        };
        boolean report = event.reportId() != null;
        UUID resourceId = report ? event.reportId() : event.documentId();
        String dedupeKey = type + ":" + resourceId;
        if (notifications.existsByUserIdAndDedupeKey(owner, dedupeKey)) {
            return;
        }
        String payload = json.writeValueAsString(Map.of(
                "resourceType", report ? "LABORATORY_REPORT" : "DOCUMENT",
                "resourceId", resourceId.toString()));
        notifications.save(new Notification(Ids.newId(), owner, type, payload, dedupeKey,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
    }
}

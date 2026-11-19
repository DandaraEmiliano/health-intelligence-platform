package dev.healthintelligence.core.documents;

import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.identity.AccountPurgeRequested;
import dev.healthintelligence.core.patient.PatientDirectory;
import dev.healthintelligence.core.shared.Outbox;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes a purged account's files from S3 and drops extraction requests not sent yet. The rows
 * go with the account (cascade). The AI index is purged by ai-service when it exists (Phase 5).
 */
@Component
class DocumentPurge {

    private final PatientDirectory patients;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final Outbox outbox;

    DocumentPurge(PatientDirectory patients, DocumentRepository documents, DocumentStorage storage, Outbox outbox) {
        this.patients = patients;
        this.documents = documents;
        this.storage = storage;
        this.outbox = outbox;
    }

    /** Runs inside the purge transaction; an S3 failure throws and the purge is retried later. */
    @EventListener
    void on(AccountPurgeRequested event) {
        patients.findId(event.user()).ifPresent(patient -> {
            List<HealthDocument> all = documents.findAllIncludingDeleted(patient);
            for (HealthDocument document : all) {
                storage.delete(document.storageKey());
            }
            outbox.discardUnpublished(all.stream().map(HealthDocument::id).map(UUID.class::cast).toList());
        });
    }
}

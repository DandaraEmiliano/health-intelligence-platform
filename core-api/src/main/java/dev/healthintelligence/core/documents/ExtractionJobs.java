package dev.healthintelligence.core.documents;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Completing extraction jobs, for the module that consumes extraction results (laboratory). */
@Component
public class ExtractionJobs {

    /** Outcome of a job as the result message states it. */
    public enum Outcome { SUCCEEDED, FAILED, UNSUPPORTED_SCANNED }

    /** The job that was just completed, and the document it belongs to. */
    public record CompletedJob(UUID jobId, UUID documentId, PatientId patientId) {
    }

    private final ExtractionJobRepository jobs;
    private final DocumentRepository documents;
    private final Clock clock;

    ExtractionJobs(ExtractionJobRepository jobs, DocumentRepository documents, Clock clock) {
        this.jobs = jobs;
        this.documents = documents;
        this.clock = clock;
    }

    /**
     * Completes the job and sets the document status, in the caller's transaction. Empty when the
     * job does not exist for that patient and document, was already completed (redelivery), or its
     * document was deleted meanwhile; the caller then stores nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CompletedJob> complete(UUID jobId, UUID documentId, PatientId patient, Outcome outcome,
                                           String failureCode) {
        Optional<ExtractionJob> found = jobs.findForUpdate(jobId, patient)
                .filter(job -> job.documentId().equals(documentId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ExtractionJob job = found.get();
        if (!job.complete(outcome.name(), outcome == Outcome.FAILED ? failureCode : null,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS))) {
            return Optional.empty();
        }
        HealthDocument document = documents.findAnyState(documentId, patient).orElse(null);
        if (document == null || document.isDeleted()) {
            return Optional.empty();
        }
        document.extractionFinished(switch (outcome) {
            case SUCCEEDED -> DocumentStatus.PROCESSED;
            case FAILED -> DocumentStatus.FAILED;
            case UNSUPPORTED_SCANNED -> DocumentStatus.UNSUPPORTED_SCANNED;
        }, failureCode);
        return Optional.of(new CompletedJob(job.id(), documentId, patient));
    }
}

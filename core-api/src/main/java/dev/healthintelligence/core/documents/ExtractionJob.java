package dev.healthintelligence.core.documents;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One extraction request; (document, extractor version) is unique, so a job is never duplicated. */
@Entity
@Table(name = "extraction_job")
class ExtractionJob {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @Column(name = "extractor_version", nullable = false, updatable = false)
    private String extractorVersion;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ExtractionJob() {
    }

    ExtractionJob(UUID id, UUID documentId, PatientId patientId, String extractorVersion, Instant requestedAt) {
        this.id = id;
        this.documentId = documentId;
        this.patientId = patientId;
        this.extractorVersion = extractorVersion;
        this.status = "REQUESTED";
        this.requestedAt = requestedAt;
    }

    /** False when the job was already completed: a redelivered result is a no-op (ADR 0003). */
    boolean complete(String outcome, String failure, Instant at) {
        if (!"REQUESTED".equals(status)) {
            return false;
        }
        status = outcome;
        failureCode = failure;
        completedAt = at;
        return true;
    }

    UUID id() { return id; }
    UUID documentId() { return documentId; }
    PatientId patientId() { return patientId; }
    String extractorVersion() { return extractorVersion; }
}

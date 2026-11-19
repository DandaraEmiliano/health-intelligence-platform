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

    UUID id() { return id; }
}

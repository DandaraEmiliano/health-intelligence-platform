package dev.healthintelligence.core.laboratory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** A report as extracted. Unverified until the patient reviews every result (ADR 0006). */
@Entity
@Table(name = "laboratory_report")
class LaboratoryReport {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "extraction_job_id", nullable = false, updatable = false)
    private UUID extractionJobId;

    @Column(name = "laboratory_name")
    private String laboratoryName;

    @Column(name = "collected_at")
    private LocalDate collectedAt;

    @Column(name = "collected_at_raw")
    private String collectedAtRaw;

    @Column(name = "reported_at")
    private LocalDate reportedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_warnings", nullable = false)
    private String validationWarnings;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected LaboratoryReport() {
    }

    LaboratoryReport(UUID id, PatientId patientId, UUID documentId, UUID extractionJobId, String laboratoryName,
                     LocalDate collectedAt, String collectedAtRaw, LocalDate reportedAt, String validationWarnings) {
        this.id = id;
        this.patientId = patientId;
        this.documentId = documentId;
        this.extractionJobId = extractionJobId;
        this.laboratoryName = laboratoryName;
        this.collectedAt = collectedAt;
        this.collectedAtRaw = collectedAtRaw;
        this.reportedAt = reportedAt;
        this.status = "PENDING_REVIEW";
        this.validationWarnings = validationWarnings;
    }

    void correctCollectedAt(LocalDate date) {
        collectedAt = date;
    }

    /** Ends the review: VERIFIED when anything was kept, REJECTED when every result was rejected. */
    void finish(boolean anythingKept, Instant at) {
        status = anythingKept ? "VERIFIED" : "REJECTED";
        verifiedAt = anythingKept ? at : null;
    }

    boolean isUnderReview() {
        return "PENDING_REVIEW".equals(status);
    }

    UUID id() { return id; }
    UUID documentId() { return documentId; }
    String laboratoryName() { return laboratoryName; }
    LocalDate collectedAt() { return collectedAt; }
    String collectedAtRaw() { return collectedAtRaw; }
    LocalDate reportedAt() { return reportedAt; }
    String status() { return status; }
    Instant verifiedAt() { return verifiedAt; }
    String validationWarnings() { return validationWarnings; }
    long version() { return version; }
}

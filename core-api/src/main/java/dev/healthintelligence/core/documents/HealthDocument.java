package dev.healthintelligence.core.documents;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A stored PDF. The file lives in S3 under an opaque key; the row never holds its content. */
@Entity
@Table(name = "health_document")
class HealthDocument {

    static final String CONTENT_TYPE = "application/pdf";

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private Long version;

    protected HealthDocument() {
    }

    HealthDocument(UUID id, PatientId patientId, String originalFilename, long sizeBytes, String sha256,
                   DocumentStatus status, Instant uploadedAt) {
        this.id = id;
        this.patientId = patientId;
        this.originalFilename = originalFilename;
        this.contentType = CONTENT_TYPE;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKeyFor(id);
        this.status = status;
        this.uploadedAt = uploadedAt;
    }

    /** Opaque key with no personal data (contracts/events: documents/&lt;uuid&gt;). */
    static String storageKeyFor(UUID id) {
        return "documents/" + id;
    }

    /** Status after extraction; failureCode is set only for FAILED (ck_health_document_failure). */
    void extractionFinished(DocumentStatus outcome, String failure) {
        status = outcome;
        failureCode = outcome == DocumentStatus.FAILED ? failure : null;
    }

    boolean isDeleted() {
        return deletedAt != null;
    }

    void delete(Instant at) {
        deletedAt = at;
    }

    UUID id() { return id; }
    PatientId patientId() { return patientId; }
    String originalFilename() { return originalFilename; }
    long sizeBytes() { return sizeBytes; }
    String sha256() { return sha256; }
    String storageKey() { return storageKey; }
    DocumentStatus status() { return status; }
    String failureCode() { return failureCode; }
    Instant uploadedAt() { return uploadedAt; }
}

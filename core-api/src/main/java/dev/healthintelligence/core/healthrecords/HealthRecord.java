package dev.healthintelligence.core.healthrecords;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Columns every patient-declared record shares. The owner never changes after creation. */
@MappedSuperclass
abstract class HealthRecord<R> {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected HealthRecord() {
    }

    protected HealthRecord(UUID id, PatientId patientId) {
        this.id = id;
        this.patientId = patientId;
    }

    /** Replaces every editable field (PUT semantics). */
    abstract void replaceWith(R request);

    UUID id() {
        return id;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    long version() {
        return version;
    }
}

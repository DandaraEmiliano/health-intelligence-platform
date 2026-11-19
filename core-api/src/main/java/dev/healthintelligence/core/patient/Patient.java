package dev.healthintelligence.core.patient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Patient profile. Exactly one per user account (uq_patient_user). */
@Entity
@Table(name = "patient")
class Patient {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "biological_sex")
    private BiologicalSex biologicalSex;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by the touch_updated_at trigger and read back after every write. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Patient() {
    }

    Patient(UUID id, UUID userId, PatientProfile profile) {
        this.id = id;
        this.userId = userId;
        apply(profile);
    }

    void apply(PatientProfile profile) {
        this.fullName = profile.fullName();
        this.birthDate = profile.birthDate();
        this.biologicalSex = profile.biologicalSex();
        this.heightCm = profile.heightCm();
    }

    PatientProfile profile() {
        return new PatientProfile(fullName, birthDate, biologicalSex, heightCm);
    }

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

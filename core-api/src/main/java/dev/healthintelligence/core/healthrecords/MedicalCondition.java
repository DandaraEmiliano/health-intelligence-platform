package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** A condition as the patient declares it. The platform does not diagnose. */
@Entity
@Table(name = "medical_condition")
class MedicalCondition extends HealthRecord<MedicalConditionRequest> {

    enum Status { ACTIVE, RESOLVED, UNKNOWN }

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "onset_date_approx")
    private LocalDate onsetDateApprox;

    protected MedicalCondition() {
    }

    MedicalCondition(UUID id, PatientId patientId, MedicalConditionRequest request) {
        super(id, patientId);
        replaceWith(request);
    }

    @Override
    void replaceWith(MedicalConditionRequest request) {
        description = request.description().strip();
        status = request.status();
        onsetDateApprox = request.onsetDateApprox();
    }

    String description() { return description; }
    Status status() { return status; }
    LocalDate onsetDateApprox() { return onsetDateApprox; }
}

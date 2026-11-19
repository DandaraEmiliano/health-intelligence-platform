package dev.healthintelligence.core.healthrecords;

import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "allergy")
class Allergy extends HealthRecord<AllergyRequest> {

    enum Type { MEDICATION, FOOD, ENVIRONMENTAL, OTHER }

    enum Severity { MILD, MODERATE, SEVERE, UNKNOWN }

    @Column(nullable = false)
    private String substance;

    @Enumerated(EnumType.STRING)
    @Column(name = "allergy_type", nullable = false)
    private Type allergyType;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String notes;

    protected Allergy() {
    }

    Allergy(UUID id, PatientId patientId, AllergyRequest request) {
        super(id, patientId);
        replaceWith(request);
    }

    @Override
    void replaceWith(AllergyRequest request) {
        substance = request.substance().strip();
        allergyType = request.allergyType();
        severity = request.severity();
        notes = Text.blankToNull(request.notes());
    }

    String substance() { return substance; }
    Type allergyType() { return allergyType; }
    Severity severity() { return severity; }
    String notes() { return notes; }
}

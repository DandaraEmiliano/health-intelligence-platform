package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** What the patient reports taking, as free text. Never interpreted or validated as a dosage. */
@Entity
@Table(name = "medication")
class Medication extends HealthRecord<MedicationRequest> {

    @Column(nullable = false)
    private String name;

    @Column(name = "dosage_text")
    private String dosageText;

    @Column(name = "frequency_text")
    private String frequencyText;

    @Column(name = "started_on")
    private LocalDate startedOn;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    protected Medication() {
    }

    Medication(UUID id, PatientId patientId, MedicationRequest request) {
        super(id, patientId);
        replaceWith(request);
    }

    @Override
    void replaceWith(MedicationRequest request) {
        name = request.name().strip();
        dosageText = Text.blankToNull(request.dosageText());
        frequencyText = Text.blankToNull(request.frequencyText());
        startedOn = request.startedOn();
        endedOn = request.endedOn();
    }

    String name() { return name; }
    String dosageText() { return dosageText; }
    String frequencyText() { return frequencyText; }
    LocalDate startedOn() { return startedOn; }
    LocalDate endedOn() { return endedOn; }
}

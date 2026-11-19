package dev.healthintelligence.core.healthrecords;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record MedicationResponse(UUID id, String name, String dosageText, String frequencyText, LocalDate startedOn,
                          LocalDate endedOn, Instant createdAt, Instant updatedAt) {

    static MedicationResponse of(Medication medication) {
        return new MedicationResponse(medication.id(), medication.name(), medication.dosageText(),
                medication.frequencyText(), medication.startedOn(), medication.endedOn(), medication.createdAt(),
                medication.updatedAt());
    }
}

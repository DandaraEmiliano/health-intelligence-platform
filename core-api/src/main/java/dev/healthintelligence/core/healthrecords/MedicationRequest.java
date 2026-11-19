package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record MedicationRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String dosageText,
        @Size(max = 200) String frequencyText,
        LocalDate startedOn,
        LocalDate endedOn) {
}

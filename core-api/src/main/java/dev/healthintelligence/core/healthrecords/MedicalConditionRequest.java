package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record MedicalConditionRequest(
        @NotBlank @Size(max = 500) String description,
        @NotNull MedicalCondition.Status status,
        LocalDate onsetDateApprox) {
}

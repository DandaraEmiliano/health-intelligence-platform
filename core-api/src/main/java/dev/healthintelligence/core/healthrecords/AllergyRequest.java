package dev.healthintelligence.core.healthrecords;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record AllergyRequest(
        @NotBlank @Size(max = 200) String substance,
        @NotNull Allergy.Type allergyType,
        Allergy.Severity severity,
        @Size(max = 1000) String notes) {
}

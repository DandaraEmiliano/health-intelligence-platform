package dev.healthintelligence.core.healthrecords;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record AllergyResponse(UUID id, String substance, Allergy.Type allergyType, Allergy.Severity severity,
                       String notes, Instant createdAt, Instant updatedAt) {

    static AllergyResponse of(Allergy allergy) {
        return new AllergyResponse(allergy.id(), allergy.substance(), allergy.allergyType(), allergy.severity(),
                allergy.notes(), allergy.createdAt(), allergy.updatedAt());
    }
}

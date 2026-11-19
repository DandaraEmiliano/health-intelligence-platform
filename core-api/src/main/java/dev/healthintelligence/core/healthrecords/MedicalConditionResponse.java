package dev.healthintelligence.core.healthrecords;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record MedicalConditionResponse(UUID id, String description, MedicalCondition.Status status,
                                LocalDate onsetDateApprox, Instant createdAt, Instant updatedAt) {

    static MedicalConditionResponse of(MedicalCondition condition) {
        return new MedicalConditionResponse(condition.id(), condition.description(), condition.status(),
                condition.onsetDateApprox(), condition.createdAt(), condition.updatedAt());
    }
}

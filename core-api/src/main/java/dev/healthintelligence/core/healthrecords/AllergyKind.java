package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientId;
import org.springframework.stereotype.Component;

@Component
class AllergyKind implements HealthRecordKind<Allergy, AllergyRequest> {

    private final AllergyRepository repository;

    AllergyKind(AllergyRepository repository) {
        this.repository = repository;
    }

    @Override public String resourceType() { return "ALLERGY"; }
    @Override public String notFoundCode() { return "ALLERGY_NOT_FOUND"; }
    @Override public HealthRecordRepository<Allergy> repository() { return repository; }

    @Override
    public Allergy create(UUID id, PatientId owner, AllergyRequest request) {
        return new Allergy(id, owner, request);
    }

    @Override
    public List<FieldViolation> validate(AllergyRequest request, LocalDate today) {
        return List.of();
    }
}

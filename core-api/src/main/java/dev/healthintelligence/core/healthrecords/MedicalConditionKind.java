package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientId;
import org.springframework.stereotype.Component;

@Component
class MedicalConditionKind implements HealthRecordKind<MedicalCondition, MedicalConditionRequest> {

    private final MedicalConditionRepository repository;

    MedicalConditionKind(MedicalConditionRepository repository) {
        this.repository = repository;
    }

    @Override public String resourceType() { return "MEDICAL_CONDITION"; }
    @Override public String notFoundCode() { return "CONDITION_NOT_FOUND"; }
    @Override public HealthRecordRepository<MedicalCondition> repository() { return repository; }

    @Override
    public MedicalCondition create(UUID id, PatientId owner, MedicalConditionRequest request) {
        return new MedicalCondition(id, owner, request);
    }

    @Override
    public List<FieldViolation> validate(MedicalConditionRequest request, LocalDate today) {
        List<FieldViolation> errors = new ArrayList<>();
        Dates.notInFuture("onsetDateApprox", request.onsetDateApprox(), today, errors);
        return errors;
    }
}

package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientId;
import org.springframework.stereotype.Component;

@Component
class MedicationKind implements HealthRecordKind<Medication, MedicationRequest> {

    private final MedicationRepository repository;

    MedicationKind(MedicationRepository repository) {
        this.repository = repository;
    }

    @Override public String resourceType() { return "MEDICATION"; }
    @Override public String notFoundCode() { return "MEDICATION_NOT_FOUND"; }
    @Override public HealthRecordRepository<Medication> repository() { return repository; }

    @Override
    public Medication create(UUID id, PatientId owner, MedicationRequest request) {
        return new Medication(id, owner, request);
    }

    @Override
    public List<FieldViolation> validate(MedicationRequest request, LocalDate today) {
        List<FieldViolation> errors = new ArrayList<>();
        Dates.notInFuture("startedOn", request.startedOn(), today, errors);
        Dates.notInFuture("endedOn", request.endedOn(), today, errors);
        if (request.startedOn() != null && request.endedOn() != null && request.endedOn().isBefore(request.startedOn())) {
            errors.add(new FieldViolation("endedOn", "INVALID_PERIOD", "endedOn must be on or after startedOn"));
        }
        return errors;
    }
}

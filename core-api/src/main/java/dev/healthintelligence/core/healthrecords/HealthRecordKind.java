package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientId;

/** What differs between allergies, medications and conditions; the use cases are shared. */
interface HealthRecordKind<T extends HealthRecord<R>, R> {

    /** Audit resource type and action prefix, e.g. {@code ALLERGY}. */
    String resourceType();

    /** Problem code when the record does not exist or belongs to another patient. */
    String notFoundCode();

    HealthRecordRepository<T> repository();

    T create(UUID id, PatientId owner, R request);

    /** Rules Bean Validation cannot express (dates relative to today, periods). */
    List<FieldViolation> validate(R request, LocalDate today);
}

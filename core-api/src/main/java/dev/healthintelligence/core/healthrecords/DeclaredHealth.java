package dev.healthintelligence.core.healthrecords;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the patient declared about themselves (allergies, medications, conditions), read-only, for
 * other modules such as the assistant's fact pack (ADR 0004). Free text is passed as entered.
 */
@Component
public class DeclaredHealth {

    public record AllergyFact(String substance, String type, String severity) {
    }

    /** {@code active}: no end date, or an end date that has not passed. */
    public record MedicationFact(String name, String dosageText, String frequencyText, boolean active) {
    }

    public record ConditionFact(String description, String status) {
    }

    public record Summary(List<AllergyFact> allergies, List<MedicationFact> medications,
                          List<ConditionFact> conditions) {
    }

    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final MedicalConditionRepository conditions;
    private final Clock clock;

    DeclaredHealth(AllergyRepository allergies, MedicationRepository medications,
                   MedicalConditionRepository conditions, Clock clock) {
        this.allergies = allergies;
        this.medications = medications;
        this.conditions = conditions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary of(PatientId patient) {
        LocalDate today = LocalDate.now(clock);
        return new Summary(
                allergies.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream()
                        .map(a -> new AllergyFact(a.substance(), name(a.allergyType()), name(a.severity())))
                        .toList(),
                medications.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream()
                        .map(m -> new MedicationFact(m.name(), m.dosageText(), m.frequencyText(),
                                m.endedOn() == null || !m.endedOn().isBefore(today)))
                        .toList(),
                conditions.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream()
                        .map(c -> new ConditionFact(c.description(), name(c.status())))
                        .toList());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

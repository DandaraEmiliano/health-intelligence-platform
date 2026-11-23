package dev.healthintelligence.core.healthrecords;

import java.util.List;
import java.util.Map;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class HealthRecordsDataExport implements PersonalDataExport {

    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final MedicalConditionRepository conditions;

    HealthRecordsDataExport(AllergyRepository allergies, MedicationRepository medications,
                            MedicalConditionRepository conditions) {
        this.allergies = allergies;
        this.medications = medications;
        this.conditions = conditions;
    }

    @Override
    public String section() {
        return "healthRecords";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        if (patient == null) {
            return Map.of("allergies", List.of(), "medications", List.of(), "conditions", List.of());
        }
        return Map.of(
                "allergies", allergies.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream().map(AllergyResponse::of).toList(),
                "medications", medications.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream().map(MedicationResponse::of).toList(),
                "conditions", conditions.findByPatientIdOrderByCreatedAtAscIdAsc(patient).stream().map(MedicalConditionResponse::of).toList());
    }
}

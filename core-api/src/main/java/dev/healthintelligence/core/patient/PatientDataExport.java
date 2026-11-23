package dev.healthintelligence.core.patient;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PatientDataExport implements PersonalDataExport {

    private final PatientRepository patients;

    PatientDataExport(PatientRepository patients) {
        this.patients = patients;
    }

    @Override
    public String section() {
        return "profile";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        return patients.findByUserId(user).map(PatientController.PatientResponse::of).orElse(null);
    }
}

package dev.healthintelligence.core.patient;

import java.util.Optional;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;

/** Patient of a given account, for background work that has no request token (the purge). */
@Component
public class PatientDirectory {

    private final PatientService patients;

    PatientDirectory(PatientService patients) {
        this.patients = patients;
    }

    public Optional<PatientId> findId(UserId owner) {
        return patients.findId(owner);
    }
}

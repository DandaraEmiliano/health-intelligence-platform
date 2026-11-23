package dev.healthintelligence.core.patient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.Optional;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient facts for other modules: the patient of an account (background work with no request
 * token, such as the purge) and the demographics the assistant may use (ADR 0004).
 */
@Component
public class PatientDirectory {

    /** Age in whole years and declared biological sex (null when not given). Never name or birth date. */
    public record Demographics(int ageYears, BiologicalSex biologicalSex) {
    }

    private final PatientService patients;
    private final PatientRepository repository;
    private final Clock clock;

    PatientDirectory(PatientService patients, PatientRepository repository, Clock clock) {
        this.patients = patients;
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<PatientId> findId(UserId owner) {
        return patients.findId(owner);
    }

    /** The account that owns a patient, for background work such as notifications. */
    @Transactional(readOnly = true)
    public Optional<UserId> ownerOf(PatientId patient) {
        return repository.findByPatientId(patient).map(Patient::userId);
    }

    @Transactional(readOnly = true)
    public Optional<Demographics> demographics(UserId owner) {
        return repository.findByUserId(owner).map(patient -> {
            PatientProfile profile = patient.profile();
            int age = Period.between(profile.birthDate(), LocalDate.now(clock)).getYears();
            return new Demographics(Math.max(0, age), profile.biologicalSex());
        });
    }
}

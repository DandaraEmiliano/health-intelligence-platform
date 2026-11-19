package dev.healthintelligence.core.patient;

import dev.healthintelligence.core.identity.CurrentUser;
import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;

/**
 * The authenticated user's patient, resolved once per call from the token (ADR 0011). Clinical
 * modules use it from their web layer and pass the {@link PatientId} down to repositories.
 */
@Component
public class CurrentPatient {

    private final CurrentUser currentUser;
    private final PatientService patients;

    CurrentPatient(CurrentUser currentUser, PatientService patients) {
        this.currentUser = currentUser;
        this.patients = patients;
    }

    /** Fails with 404 {@code PATIENT_NOT_FOUND} until onboarding has created the profile. */
    public PatientScope scope() {
        UserId user = currentUser.id();
        PatientId patient = patients.findId(user)
                .orElseThrow(() -> ProblemException.notFound(PatientService.NOT_FOUND));
        return new PatientScope(user, patient);
    }
}

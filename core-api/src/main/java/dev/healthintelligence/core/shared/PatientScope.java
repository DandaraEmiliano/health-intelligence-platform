package dev.healthintelligence.core.shared;

import java.util.Objects;

/**
 * Who is acting ({@code user}, for the audit trail) and whose data is in scope ({@code patient},
 * for every query). Resolved from the token, never from the request (ADR 0011).
 */
public record PatientScope(UserId user, PatientId patient) {

    public PatientScope {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(patient, "patient");
    }
}

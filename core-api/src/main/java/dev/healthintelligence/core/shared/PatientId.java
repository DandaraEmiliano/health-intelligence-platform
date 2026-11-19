package dev.healthintelligence.core.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Patient identifier. Repository methods for patient-owned data take it as a required parameter
 * (ADR 0011); it is always resolved from the token, never read from the client.
 */
public record PatientId(UUID value) {

    public PatientId {
        Objects.requireNonNull(value, "value");
    }
}

package dev.healthintelligence.core.shared;

import java.util.Objects;
import java.util.UUID;

/** Local user account identifier (not the identity provider subject). */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "value");
    }
}

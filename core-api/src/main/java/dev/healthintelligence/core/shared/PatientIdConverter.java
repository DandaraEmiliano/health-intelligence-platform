package dev.healthintelligence.core.shared;

import java.util.UUID;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Lets entities hold {@link PatientId} and repositories take it as a query parameter, so a
 * patient-scoped lookup cannot be written with a raw UUID by mistake (ADR 0011).
 */
@Converter(autoApply = true)
public class PatientIdConverter implements AttributeConverter<PatientId, UUID> {

    @Override
    public UUID convertToDatabaseColumn(PatientId id) {
        return id == null ? null : id.value();
    }

    @Override
    public PatientId convertToEntityAttribute(UUID value) {
        return value == null ? null : new PatientId(value);
    }
}

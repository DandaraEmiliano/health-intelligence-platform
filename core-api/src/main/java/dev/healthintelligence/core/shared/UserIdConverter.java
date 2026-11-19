package dev.healthintelligence.core.shared;

import java.util.UUID;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Same as {@link PatientIdConverter}, for data owned by a user account. */
@Converter(autoApply = true)
public class UserIdConverter implements AttributeConverter<UserId, UUID> {

    @Override
    public UUID convertToDatabaseColumn(UserId id) {
        return id == null ? null : id.value();
    }

    @Override
    public UserId convertToEntityAttribute(UUID value) {
        return value == null ? null : new UserId(value);
    }
}

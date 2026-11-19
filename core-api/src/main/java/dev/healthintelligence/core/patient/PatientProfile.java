package dev.healthintelligence.core.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;

/**
 * The editable profile fields and the rules they must satisfy, shared by create and update so
 * both validate the same way. Messages name the rule, never the submitted value.
 */
record PatientProfile(String fullName, LocalDate birthDate, BiologicalSex biologicalSex, BigDecimal heightCm) {

    static final int FULL_NAME_MAX = 200;
    static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    static final BigDecimal HEIGHT_MIN = new BigDecimal("30");
    static final BigDecimal HEIGHT_MAX = new BigDecimal("272");

    /** Normalises (trims the name) and validates; throws a 400 listing every invalid field. */
    PatientProfile validated(LocalDate today) {
        List<FieldViolation> errors = new ArrayList<>();
        String name = fullName == null ? null : fullName.strip();
        if (name == null || name.isEmpty()) {
            errors.add(new FieldViolation("fullName", "REQUIRED", "fullName is required"));
        } else if (name.length() > FULL_NAME_MAX) {
            errors.add(new FieldViolation("fullName", "INVALID_LENGTH", "fullName must have at most 200 characters"));
        }
        if (birthDate == null) {
            errors.add(new FieldViolation("birthDate", "REQUIRED", "birthDate is required"));
        } else if (!birthDate.isBefore(today)) {
            errors.add(new FieldViolation("birthDate", "PAST_DATE_REQUIRED", "birthDate must be in the past"));
        } else if (birthDate.isBefore(EARLIEST_BIRTH_DATE)) {
            errors.add(new FieldViolation("birthDate", "OUT_OF_RANGE", "birthDate must be on or after 1900-01-01"));
        }
        if (heightCm != null) {
            if (heightCm.compareTo(HEIGHT_MIN) < 0 || heightCm.compareTo(HEIGHT_MAX) > 0) {
                errors.add(new FieldViolation("heightCm", "OUT_OF_RANGE", "heightCm must be between 30 and 272"));
            } else if (heightCm.stripTrailingZeros().scale() > 1) {
                errors.add(new FieldViolation("heightCm", "INVALID_PRECISION", "heightCm allows one decimal place"));
            }
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
        BigDecimal height = heightCm == null ? null : heightCm.setScale(1);
        return new PatientProfile(name, birthDate, biologicalSex, height);
    }
}

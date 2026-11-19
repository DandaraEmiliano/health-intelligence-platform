package dev.healthintelligence.core.patient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import tools.jackson.databind.JsonNode;

/**
 * JSON Merge Patch (RFC 7396) for the profile: a member present with a value replaces the field,
 * {@code null} clears an optional field, an absent member leaves it unchanged.
 */
final class PatientPatch {

    private static final Set<String> FIELDS = Set.of("fullName", "birthDate", "biologicalSex", "heightCm");

    private final JsonNode patch;

    private PatientPatch(JsonNode patch) {
        this.patch = patch;
    }

    /** Checks the document shape (an object with known members of the right JSON types). */
    static PatientPatch parse(JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw ProblemException.validation(List.of(
                    new FieldViolation("", "INVALID_FORMAT", "body must be a JSON object with at least one member")));
        }
        List<FieldViolation> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> member : patch.properties()) {
            String name = member.getKey();
            JsonNode value = member.getValue();
            if (!FIELDS.contains(name)) {
                errors.add(new FieldViolation(name, "UNKNOWN_FIELD", name + " is not an updatable field"));
            } else if (value.isNull()) {
                if (name.equals("fullName") || name.equals("birthDate")) {
                    errors.add(new FieldViolation(name, "REQUIRED", name + " cannot be removed"));
                }
            } else if (name.equals("heightCm") ? !value.isNumber() : !value.isString()) {
                errors.add(new FieldViolation(name, "INVALID_TYPE", name + " has the wrong type"));
            }
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
        return new PatientPatch(patch);
    }

    PatientProfile applyTo(PatientProfile current) {
        List<FieldViolation> errors = new ArrayList<>();
        String fullName = has("fullName") ? patch.get("fullName").stringValue() : current.fullName();
        LocalDate birthDate = current.birthDate();
        if (has("birthDate")) {
            try {
                birthDate = LocalDate.parse(patch.get("birthDate").stringValue());
            } catch (DateTimeParseException invalid) {
                errors.add(new FieldViolation("birthDate", "INVALID_FORMAT", "birthDate must be an ISO 8601 date"));
            }
        }
        BiologicalSex sex = current.biologicalSex();
        if (has("biologicalSex")) {
            JsonNode value = patch.get("biologicalSex");
            sex = value.isNull() ? null : parseSex(value.stringValue(), errors);
        }
        BigDecimal height = current.heightCm();
        if (has("heightCm")) {
            JsonNode value = patch.get("heightCm");
            height = value.isNull() ? null : value.decimalValue();
        }
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
        return new PatientProfile(fullName, birthDate, sex, height);
    }

    /** Names of the members present, for the audit trail (never their values). */
    Set<String> fieldNames() {
        Set<String> names = new TreeSet<>();
        patch.properties().forEach(member -> names.add(member.getKey()));
        return names;
    }

    private boolean has(String field) {
        return patch.has(field);
    }

    private static BiologicalSex parseSex(String value, List<FieldViolation> errors) {
        try {
            return BiologicalSex.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            errors.add(new FieldViolation("biologicalSex", "INVALID_VALUE", "biologicalSex is not a known value"));
            return null;
        }
    }
}

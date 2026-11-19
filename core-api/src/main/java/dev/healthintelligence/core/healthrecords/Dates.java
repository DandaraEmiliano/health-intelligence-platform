package dev.healthintelligence.core.healthrecords;

import java.time.LocalDate;
import java.util.List;

import dev.healthintelligence.core.shared.FieldViolation;

final class Dates {

    static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private Dates() {
    }

    static void notInFuture(String field, LocalDate date, LocalDate today, List<FieldViolation> errors) {
        if (date == null) {
            return;
        }
        if (date.isAfter(today)) {
            errors.add(new FieldViolation(field, "FUTURE_DATE_NOT_ALLOWED", field + " cannot be in the future"));
        } else if (date.isBefore(EARLIEST)) {
            errors.add(new FieldViolation(field, "OUT_OF_RANGE", field + " must be on or after 1900-01-01"));
        }
    }
}

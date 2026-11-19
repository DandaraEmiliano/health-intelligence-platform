package dev.healthintelligence.core.laboratory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** ReviewRequest of the contract. Shape rules here; cross-field rules in {@link ReviewService}. */
record ReviewRequest(
        @NotNull @Size(max = 500) List<@Valid Decision> decisions,
        Boolean confirmAllRemaining,
        @JsonProperty("finalize") Boolean finalizeReview,
        LocalDate collectedAt) {

    enum Action { CONFIRM, CORRECT, REJECT }

    record Decision(@NotNull UUID resultId, @NotNull Action action, @Valid Correction correction) {
    }

    record Correction(UUID analyteId, @NotNull @Valid Measurement measurement, @Size(max = 50) String unit,
                      @NotNull @Valid Range referenceRange) {
    }

    record Measurement(
            @NotNull @Pattern(regexp = "NUMERIC|TEXT") String kind,
            @NotNull @Size(min = 1, max = 200) String rawValue,
            @Pattern(regexp = DECIMAL) String valueNumeric,
            @Pattern(regexp = "<|<=|>|>=") String comparator,
            @Size(min = 1, max = 200) String valueText) {
    }

    record Range(@Size(max = 1000) String rawText, @Pattern(regexp = DECIMAL) String low,
                 @Pattern(regexp = DECIMAL) String high, @Size(max = 200) String appliesTo) {
    }

    static final String DECIMAL = "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$";

    boolean confirmsAllRemaining() {
        return Boolean.TRUE.equals(confirmAllRemaining);
    }

    boolean finalizes() {
        return Boolean.TRUE.equals(finalizeReview);
    }
}

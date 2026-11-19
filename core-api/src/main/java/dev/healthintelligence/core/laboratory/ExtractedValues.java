package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;

/** The reviewable fields of one result, as stored. */
record ExtractedValues(
        int sequence,
        String rawName,
        String valueKind,
        String valueRaw,
        BigDecimal valueNumeric,
        String valueComparator,
        String valueText,
        String unitRaw,
        String unitNormalized,
        String refRangeText,
        BigDecimal refLow,
        BigDecimal refHigh,
        String refAppliesTo,
        String labFlag,
        String computedFlag,
        String method,
        int sourcePage,
        String sourceExcerpt) {
}

package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One extracted result. The extractor's output is kept unchanged in {@code extracted_snapshot};
 * the columns are what the patient reviews and, once verified, what everything else reads.
 */
@Entity
@Table(name = "laboratory_result")
class LaboratoryResult {

    @Id
    private UUID id;

    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private PatientId patientId;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "analyte_id")
    private UUID analyteId;

    @Column(name = "raw_name", nullable = false)
    private String rawName;

    @Column(name = "value_kind", nullable = false)
    private String valueKind;

    @Column(name = "value_raw", nullable = false)
    private String valueRaw;

    @Column(name = "value_numeric", precision = 20, scale = 6)
    private BigDecimal valueNumeric;

    @Column(name = "value_comparator")
    private String valueComparator;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "unit_raw")
    private String unitRaw;

    @Column(name = "unit_normalized")
    private String unitNormalized;

    @Column(name = "ref_range_text")
    private String refRangeText;

    @Column(name = "ref_low", precision = 20, scale = 6)
    private BigDecimal refLow;

    @Column(name = "ref_high", precision = 20, scale = 6)
    private BigDecimal refHigh;

    @Column(name = "ref_applies_to")
    private String refAppliesTo;

    @Column(name = "lab_flag")
    private String labFlag;

    @Column(name = "computed_flag")
    private String computedFlag;

    @Column
    private String method;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_snapshot", nullable = false)
    private String extractedSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_warnings", nullable = false)
    private String validationWarnings;

    @Column(name = "source_page", nullable = false)
    private int sourcePage;

    @Column(name = "source_excerpt", nullable = false)
    private String sourceExcerpt;

    @Column(name = "collected_at")
    private LocalDate collectedAt;

    @Version
    private Long version;

    protected LaboratoryResult() {
    }

    LaboratoryResult(UUID id, UUID reportId, PatientId patientId, LocalDate collectedAt, UUID analyteId,
                     ExtractedValues values, String extractedSnapshot, String validationWarnings) {
        this.id = id;
        this.analyteId = analyteId;
        this.reportId = reportId;
        this.patientId = patientId;
        this.collectedAt = collectedAt;
        this.sequence = values.sequence();
        this.rawName = values.rawName();
        this.valueKind = values.valueKind();
        this.valueRaw = values.valueRaw();
        this.valueNumeric = values.valueNumeric();
        this.valueComparator = values.valueComparator();
        this.valueText = values.valueText();
        this.unitRaw = values.unitRaw();
        this.unitNormalized = values.unitNormalized();
        this.refRangeText = values.refRangeText();
        this.refLow = values.refLow();
        this.refHigh = values.refHigh();
        this.refAppliesTo = values.refAppliesTo();
        this.labFlag = values.labFlag();
        this.computedFlag = values.computedFlag();
        this.method = values.method();
        this.sourcePage = values.sourcePage();
        this.sourceExcerpt = values.sourceExcerpt();
        this.reviewStatus = "PENDING_REVIEW";
        this.extractedSnapshot = extractedSnapshot;
        this.validationWarnings = validationWarnings;
    }

    void confirm(Instant at) {
        reviewStatus = "VERIFIED";
        reviewedAt = at;
    }

    void reject(Instant at) {
        reviewStatus = "REJECTED";
        reviewedAt = at;
    }

    /** The extracted snapshot stays as it was; only the reviewable columns change (ADR 0006). */
    void correct(ExtractedValues corrected, Instant at) {
        valueKind = corrected.valueKind();
        valueRaw = corrected.valueRaw();
        valueNumeric = corrected.valueNumeric();
        valueComparator = corrected.valueComparator();
        valueText = corrected.valueText();
        unitRaw = corrected.unitRaw();
        unitNormalized = corrected.unitNormalized();
        refRangeText = corrected.refRangeText();
        refLow = corrected.refLow();
        refHigh = corrected.refHigh();
        refAppliesTo = corrected.refAppliesTo();
        computedFlag = corrected.computedFlag();
        reviewStatus = "CORRECTED";
        reviewedAt = at;
    }

    void assignAnalyte(UUID analyte) {
        analyteId = analyte;
    }

    UUID analyteId() {
        return analyteId;
    }

    void moveCollectedAt(LocalDate date) {
        collectedAt = date;
    }

    String extractedSnapshot() {
        return extractedSnapshot;
    }

    ExtractedValues values() {
        return new ExtractedValues(sequence, rawName, valueKind, valueRaw, valueNumeric, valueComparator, valueText,
                unitRaw, unitNormalized, refRangeText, refLow, refHigh, refAppliesTo, labFlag, computedFlag, method,
                sourcePage, sourceExcerpt);
    }

    UUID id() { return id; }
    UUID reportId() { return reportId; }
    LocalDate collectedAt() { return collectedAt; }
    String reviewStatus() { return reviewStatus; }
    String validationWarnings() { return validationWarnings; }
}

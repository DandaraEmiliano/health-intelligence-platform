package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified laboratory data for other modules (timeline, analytics, later the assistant). Only
 * results the patient confirmed or corrected in a finished review ever leave this module (ADR 0006).
 */
@Component
public class VerifiedResults {

    private static final Set<String> OUT_OF_RANGE = Set.of("HIGH", "LOW", "ABNORMAL", "CRITICAL");

    public record AnalyteInfo(UUID id, String code, String displayName, String category) {
    }

    /**
     * One verified numeric or text value. Units are as printed; never converted (ADR 0007).
     * {@code rawName} and {@code valueRaw} are as printed on the report (or as the patient corrected them).
     */
    public record Point(UUID resultId, UUID reportId, LocalDate collectedAt, String kind, BigDecimal value,
                        String comparator, String valueText, String unit, String unitNormalized, String rangeText,
                        BigDecimal low, BigDecimal high, String appliesTo, String flag, String flagSource,
                        String laboratoryName, AnalyteInfo analyte, String rawName, String valueRaw) {

        public boolean outOfRange() {
            return flag != null && OUT_OF_RANGE.contains(flag);
        }
    }

    public record ReportEntry(UUID id, LocalDate collectedAt, String laboratoryName, String status, int resultCount,
                              int outOfRangeCount) {
    }

    private final LaboratoryResultRepository results;
    private final LaboratoryReportRepository reports;
    private final AnalyteCatalog catalog;

    VerifiedResults(LaboratoryResultRepository results, LaboratoryReportRepository reports, AnalyteCatalog catalog) {
        this.results = results;
        this.reports = reports;
        this.catalog = catalog;
    }

    public Optional<AnalyteInfo> analyte(UUID id) {
        return catalog.find(id).map(VerifiedResults::info);
    }

    /** Oldest first; with an analyte id, only that analyte. */
    @Transactional(readOnly = true)
    public List<Point> points(PatientId patient, UUID analyteId, LocalDate from, LocalDate to) {
        List<LaboratoryResult> rows = results.findVerified(patient, analyteId, from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, LaboratoryReport> byId = reports.findByIds(rows.stream().map(LaboratoryResult::reportId).collect(Collectors.toSet()), patient)
                .stream().collect(Collectors.toMap(LaboratoryReport::id, Function.identity()));
        return rows.stream().map(r -> point(r, byId.get(r.reportId()))).toList();
    }

    /** Finished reviews, newest first, with how many kept results are flagged out of range. */
    @Transactional(readOnly = true)
    public List<ReportEntry> reports(PatientId patient, LocalDate afterDate, UUID afterId, LocalDate from, LocalDate to,
                                     int limit) {
        List<LaboratoryReport> page = reports.verifiedPage(patient, afterDate, afterId, from, to, Limit.of(limit));
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LaboratoryResult>> kept = results.findByReports(page.stream().map(LaboratoryReport::id).toList(), patient)
                .stream().filter(r -> !"REJECTED".equals(r.reviewStatus()) && !"PENDING_REVIEW".equals(r.reviewStatus()))
                .collect(Collectors.groupingBy(LaboratoryResult::reportId));
        return page.stream().map(report -> {
            List<LaboratoryResult> rows = kept.getOrDefault(report.id(), List.of());
            int flagged = (int) rows.stream().filter(r -> point(r, report).outOfRange()).count();
            return new ReportEntry(report.id(), report.collectedAt(), report.laboratoryName(), report.status(),
                    rows.size(), flagged);
        }).toList();
    }

    private Point point(LaboratoryResult result, LaboratoryReport report) {
        ExtractedValues v = result.values();
        String flag = v.labFlag() != null ? v.labFlag() : v.computedFlag();
        String source = v.labFlag() != null ? "LABORATORY" : v.computedFlag() != null ? "COMPUTED" : null;
        return new Point(result.id(), result.reportId(), result.collectedAt(), v.valueKind(), v.valueNumeric(),
                v.valueComparator(), v.valueText(), v.unitRaw(), v.unitNormalized(), v.refRangeText(), v.refLow(),
                v.refHigh(), v.refAppliesTo(), flag, source, report == null ? null : report.laboratoryName(),
                catalog.find(result.analyteId()).map(VerifiedResults::info).orElse(null), v.rawName(), v.valueRaw());
    }

    private static AnalyteInfo info(Analyte analyte) {
        return new AnalyteInfo(analyte.id(), analyte.code(), analyte.displayName(), analyte.category());
    }
}

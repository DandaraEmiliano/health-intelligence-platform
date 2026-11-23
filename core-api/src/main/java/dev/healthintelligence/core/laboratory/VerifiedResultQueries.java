package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified results for the patient: lists, out-of-range, a summary and the comparison of two
 * reports (ADR 0006: only results kept in a finished review; ADR 0007: units are never converted,
 * so a difference is computed only between identical units).
 */
@Service
class VerifiedResultQueries {

    static final Set<String> OUT_OF_RANGE = Set.of("HIGH", "LOW", "ABNORMAL", "CRITICAL");
    static final String RESULT_NOT_FOUND = "LABORATORY_RESULT_NOT_FOUND";

    /** A result with the report it came from. */
    record Row(LaboratoryResult result, LaboratoryReport report) {

        String flag() {
            ExtractedValues v = result.values();
            return v.labFlag() != null ? v.labFlag() : v.computedFlag();
        }

        boolean outOfRange() {
            return flag() != null && OUT_OF_RANGE.contains(flag());
        }
    }

    record AnalyteCount(Analyte analyte, long count) {
    }

    record Summary(long reportCount, int verifiedResultCount, long pendingReviewCount, LocalDate firstCollectedAt,
                   LocalDate lastCollectedAt, List<Analyte> mostFrequentAnalytes, List<Row> recentOutOfRange) {
    }

    enum NoDelta { UNIT_MISMATCH, NOT_NUMERIC, MISSING_SIDE, CENSORED_VALUE }

    record ComparisonRow(Analyte analyte, String label, Row left, Row right, BigDecimal delta, NoDelta reason) {
    }

    record Comparison(LaboratoryReports.ReportWithResults left, LaboratoryReports.ReportWithResults right,
                      List<ComparisonRow> rows) {
    }

    /** Newest first: by collection date, then report order. */
    static final Comparator<Row> NEWEST_FIRST = Comparator
            .comparing((Row r) -> r.result().collectedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(r -> r.result().reportId())
            .thenComparing(r -> r.result().values().sequence());

    private final LaboratoryResultRepository results;
    private final LaboratoryReportRepository reports;
    private final LaboratoryReports reportQueries;
    private final AnalyteCatalog catalog;

    VerifiedResultQueries(LaboratoryResultRepository results, LaboratoryReportRepository reports,
                          LaboratoryReports reportQueries, AnalyteCatalog catalog) {
        this.results = results;
        this.reports = reports;
        this.reportQueries = reportQueries;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    List<Row> verified(PatientScope scope, UUID analyteId, LocalDate from, LocalDate to) {
        List<LaboratoryResult> rows = results.findVerified(scope.patient(), analyteId, from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, LaboratoryReport> byId = reports.findByIds(
                        rows.stream().map(LaboratoryResult::reportId).collect(Collectors.toSet()), scope.patient())
                .stream().collect(Collectors.toMap(LaboratoryReport::id, Function.identity()));
        return rows.stream().map(r -> new Row(r, byId.get(r.reportId()))).sorted(NEWEST_FIRST).toList();
    }

    @Transactional(readOnly = true)
    Row one(PatientScope scope, UUID id) {
        LaboratoryResult result = results.findVerifiedById(id, scope.patient())
                .orElseThrow(() -> ProblemException.notFound(RESULT_NOT_FOUND));
        LaboratoryReport report = reports.findByIds(Set.of(result.reportId()), scope.patient()).getFirst();
        return new Row(result, report);
    }

    @Transactional(readOnly = true)
    Summary summary(PatientScope scope) {
        List<Row> rows = verified(scope, null, null, null);
        List<LocalDate> dates = rows.stream().map(r -> r.result().collectedAt()).filter(Objects::nonNull).sorted().toList();
        List<Analyte> frequent = rows.stream()
                .filter(r -> r.result().analyteId() != null)
                .collect(Collectors.groupingBy(r -> r.result().analyteId(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new AnalyteCount(catalog.find(e.getKey()).orElse(null), e.getValue()))
                .filter(c -> c.analyte() != null)
                .sorted(Comparator.comparingLong(AnalyteCount::count).reversed()
                        .thenComparing(c -> c.analyte().displayName()))
                .limit(10).map(AnalyteCount::analyte).toList();
        return new Summary(reports.countVisible(scope.patient()), rows.size(), reports.countPendingReview(scope.patient()),
                dates.isEmpty() ? null : dates.getFirst(), dates.isEmpty() ? null : dates.getLast(), frequent,
                rows.stream().filter(Row::outOfRange).limit(10).toList());
    }

    @Transactional(readOnly = true)
    Comparison compare(PatientScope scope, UUID leftId, UUID rightId) {
        if (leftId.equals(rightId)) {
            throw ProblemException.validation(List.of(new FieldViolation("right", "SAME_REPORT", "choose two different reports")));
        }
        LaboratoryReports.ReportWithResults left = reportQueries.get(scope, leftId);
        LaboratoryReports.ReportWithResults right = reportQueries.get(scope, rightId);
        List<FieldViolation> notVerified = new ArrayList<>();
        if (!"VERIFIED".equals(left.report().status())) {
            notVerified.add(new FieldViolation("left", "NOT_VERIFIED", "the report review is not finished"));
        }
        if (!"VERIFIED".equals(right.report().status())) {
            notVerified.add(new FieldViolation("right", "NOT_VERIFIED", "the report review is not finished"));
        }
        if (!notVerified.isEmpty()) {
            throw ProblemException.validation(notVerified);
        }

        Map<String, Row> leftByKey = kept(left);
        Map<String, Row> rightByKey = kept(right);
        List<ComparisonRow> rows = new ArrayList<>();
        leftByKey.forEach((key, l) -> rows.add(row(l, rightByKey.get(key))));
        rightByKey.forEach((key, r) -> {
            if (!leftByKey.containsKey(key)) {
                rows.add(row(null, r));
            }
        });
        return new Comparison(left, right, rows);
    }

    /** Kept results by analyte (or by printed name when not recognised), in report order; first one wins. */
    private static Map<String, Row> kept(LaboratoryReports.ReportWithResults found) {
        Map<String, Row> byKey = new LinkedHashMap<>();
        found.results().stream()
                .filter(r -> "VERIFIED".equals(r.reviewStatus()) || "CORRECTED".equals(r.reviewStatus()))
                .sorted(Comparator.comparingInt(r -> r.values().sequence()))
                .forEach(r -> byKey.putIfAbsent(key(r), new Row(r, found.report())));
        return byKey;
    }

    private static String key(LaboratoryResult r) {
        return r.analyteId() != null ? "a:" + r.analyteId()
                : "n:" + r.values().rawName().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private ComparisonRow row(Row left, Row right) {
        LaboratoryResult any = left != null ? left.result() : right.result();
        Analyte analyte = catalog.find(any.analyteId()).orElse(null);
        String label = analyte != null ? analyte.displayName() : any.values().rawName();
        if (left == null || right == null) {
            return new ComparisonRow(analyte, label, left, right, null, NoDelta.MISSING_SIDE);
        }
        ExtractedValues l = left.result().values();
        ExtractedValues r = right.result().values();
        if (!"NUMERIC".equals(l.valueKind()) || !"NUMERIC".equals(r.valueKind())) {
            return new ComparisonRow(analyte, label, left, right, null, NoDelta.NOT_NUMERIC);
        }
        if (l.valueComparator() != null || r.valueComparator() != null) {
            return new ComparisonRow(analyte, label, left, right, null, NoDelta.CENSORED_VALUE);
        }
        if (!sameUnit(l, r)) {
            return new ComparisonRow(analyte, label, left, right, null, NoDelta.UNIT_MISMATCH);
        }
        return new ComparisonRow(analyte, label, left, right, r.valueNumeric().subtract(l.valueNumeric()), null);
    }

    /** Identical units only (ADR 0007): the normalised spelling when both have one, else the printed text. */
    private static boolean sameUnit(ExtractedValues l, ExtractedValues r) {
        if (l.unitNormalized() != null && r.unitNormalized() != null) {
            return l.unitNormalized().equals(r.unitNormalized());
        }
        return Objects.equals(normalize(l.unitRaw()), normalize(r.unitRaw()));
    }

    private static String normalize(String unit) {
        return unit == null ? null : unit.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}

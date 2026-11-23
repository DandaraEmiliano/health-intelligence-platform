package dev.healthintelligence.core.laboratory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Result;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Summary;
import dev.healthintelligence.core.laboratory.VerifiedResultQueries.Row;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verified results outside a single report: the list, one result, the out-of-range view, the
 * history summary and the comparison of two reports. Nothing here interprets a value.
 */
@RestController
class VerifiedResultsController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final CurrentPatient currentPatient;
    private final VerifiedResultQueries queries;
    private final ResultViews views;

    VerifiedResultsController(CurrentPatient currentPatient, VerifiedResultQueries queries, ResultViews views) {
        this.currentPatient = currentPatient;
        this.queries = queries;
        this.views = views;
    }

    record ResultPage(List<Result> items, String nextCursor) {
    }

    record HealthSummary(long reportCount, int verifiedResultCount, long pendingReviewCount, LocalDate firstCollectedAt,
                         LocalDate lastCollectedAt, List<AnalyteController.AnalyteView> mostFrequentAnalytes,
                         List<Result> recentOutOfRange) {
    }

    record ComparisonRow(AnalyteController.AnalyteView analyte, String label, Result left, Result right, String delta,
                         String deltaUnavailableReason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReportComparison(Summary left, Summary right, List<ComparisonRow> rows) {
    }

    @GetMapping("/api/v1/laboratory-results")
    ResponseEntity<ResultPage> listLaboratoryResults(@RequestParam(name = "analyteId", required = false) UUID analyteId,
                                                     @RequestParam(name = "from", required = false) LocalDate from,
                                                     @RequestParam(name = "to", required = false) LocalDate to,
                                                     @RequestParam(name = "cursor", required = false) String cursor,
                                                     @RequestParam(name = "limit", required = false) Integer limit) {
        checkPeriod(from, to);
        return page(queries.verified(currentPatient.scope(), analyteId, from, to), cursor, limit);
    }

    @GetMapping("/api/v1/laboratory-results/{resultId}")
    ResponseEntity<Result> getLaboratoryResult(@PathVariable("resultId") UUID id) {
        Row row = queries.one(currentPatient.scope(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views.result(row.report(), row.result()));
    }

    @GetMapping("/api/v1/health-analytics/out-of-range")
    ResponseEntity<ResultPage> listOutOfRangeResults(@RequestParam(name = "from", required = false) LocalDate from,
                                                     @RequestParam(name = "to", required = false) LocalDate to,
                                                     @RequestParam(name = "cursor", required = false) String cursor,
                                                     @RequestParam(name = "limit", required = false) Integer limit) {
        checkPeriod(from, to);
        return page(queries.verified(currentPatient.scope(), null, from, to).stream().filter(Row::outOfRange).toList(),
                cursor, limit);
    }

    @GetMapping("/api/v1/health-analytics/summary")
    ResponseEntity<HealthSummary> getHealthSummary() {
        VerifiedResultQueries.Summary s = queries.summary(currentPatient.scope());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HealthSummary(s.reportCount(),
                s.verifiedResultCount(), s.pendingReviewCount(), s.firstCollectedAt(), s.lastCollectedAt(),
                s.mostFrequentAnalytes().stream().map(AnalyteController.AnalyteView::of).toList(),
                s.recentOutOfRange().stream().map(this::view).toList()));
    }

    @GetMapping("/api/v1/laboratory-reports/comparison")
    ResponseEntity<ReportComparison> compareLaboratoryReports(@RequestParam(name = "left") UUID left,
                                                              @RequestParam(name = "right") UUID right) {
        PatientScope scope = currentPatient.scope();
        VerifiedResultQueries.Comparison c = queries.compare(scope, left, right);
        List<ComparisonRow> rows = c.rows().stream().map(r -> new ComparisonRow(
                r.analyte() == null ? null : AnalyteController.AnalyteView.of(r.analyte()), r.label(),
                r.left() == null ? null : view(r.left()), r.right() == null ? null : view(r.right()),
                ResultViews.plain(r.delta()), r.reason() == null ? null : r.reason().name())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ReportComparison(Summary.of(c.left()), Summary.of(c.right()), rows));
    }

    private Result view(Row row) {
        return views.result(row.report(), row.result());
    }

    /** The whole filtered list is small for one patient; the cursor is an opaque offset into it. */
    private ResponseEntity<ResultPage> page(List<Row> rows, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ProblemException.validation(List.of(new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100")));
        }
        int offset = decode(cursor);
        List<Row> items = rows.subList(Math.min(offset, rows.size()), Math.min(offset + size, rows.size()));
        String next = offset + size < rows.size() ? encode(offset + size) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ResultPage(items.stream().map(this::view).toList(), next));
    }

    private static void checkPeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ProblemException.validation(List.of(new FieldViolation("to", "INVALID_PERIOD", "to must not be before from")));
        }
    }

    private static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = Integer.parseInt(raw.substring(raw.indexOf("o:") == 0 ? 2 : raw.length()));
            if (offset < 0) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (RuntimeException invalid) {
            throw ProblemException.validation(List.of(new FieldViolation("cursor", "INVALID_FORMAT", "cursor is not valid")));
        }
    }
}

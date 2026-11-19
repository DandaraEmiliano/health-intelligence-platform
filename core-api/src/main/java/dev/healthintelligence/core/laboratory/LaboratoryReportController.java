package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.ETags;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/laboratory-reports")
class LaboratoryReportController {

    private static final List<String> STATUSES = List.of("PENDING_REVIEW", "VERIFIED", "REJECTED");

    private final CurrentPatient currentPatient;
    private final LaboratoryReports reports;
    private final JsonMapper json;

    LaboratoryReportController(CurrentPatient currentPatient, LaboratoryReports reports, JsonMapper json) {
        this.currentPatient = currentPatient;
        this.reports = reports;
        this.json = json;
    }

    @GetMapping
    SummaryPage listLaboratoryReports(@RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "cursor", required = false) String cursor,
                                      @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw ProblemException.validation(List.of(new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100")));
        }
        if (status != null && !STATUSES.contains(status)) {
            throw ProblemException.validation(List.of(new FieldViolation("status", "INVALID_VALUE", "status is not a known value")));
        }
        var rows = reports.page(currentPatient.scope(), status, ReportCursor.decode(cursor), size);
        boolean more = rows.size() > size;
        var items = more ? rows.subList(0, size) : rows;
        return new SummaryPage(items.stream().map(Summary::of).toList(),
                more ? ReportCursor.after(items.getLast().report()).encode() : null);
    }

    @GetMapping("/{reportId}")
    ResponseEntity<Detail> getLaboratoryReport(@PathVariable("reportId") UUID id) {
        var found = reports.get(currentPatient.scope(), id);
        return ResponseEntity.ok().eTag(ETags.of(found.report().version())).body(detail(found));
    }

    private Detail detail(LaboratoryReports.ReportWithResults found) {
        LaboratoryReport report = found.report();
        Summary summary = Summary.of(found);
        return new Detail(summary.id(), summary.documentId(), summary.laboratoryName(), summary.collectedAt(),
                summary.status(), summary.resultCount(), summary.pendingCount(), report.collectedAtRaw(),
                json.readTree(report.validationWarnings()), report.verifiedAt(),
                found.results().stream().map(r -> result(report, r)).toList());
    }

    private Result result(LaboratoryReport report, LaboratoryResult result) {
        ExtractedValues v = result.values();
        Flag flag = v.labFlag() != null ? new Flag(v.labFlag(), "LABORATORY")
                : v.computedFlag() != null ? new Flag(v.computedFlag(), "COMPUTED") : null;
        return new Result(result.id(), report.id(), v.sequence(), null, v.rawName(),
                new Measurement(v.valueKind(), v.valueRaw(), plain(v.valueNumeric()), v.valueComparator(), v.valueText()),
                v.unitRaw(), new Range(v.refRangeText(), plain(v.refLow()), plain(v.refHigh()), v.refAppliesTo()),
                flag, result.collectedAt(), result.reviewStatus(), json.readTree(result.validationWarnings()),
                new Source(report.documentId(), v.sourcePage(), v.sourceExcerpt()));
    }

    /** numeric(20,6) comes back as 206.000000; the API shows the decimal without the padding. */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(UUID id, UUID documentId, String laboratoryName, LocalDate collectedAt, String status,
                   int resultCount, int pendingCount) {

        static Summary of(LaboratoryReports.ReportWithResults found) {
            LaboratoryReport r = found.report();
            int pending = (int) found.results().stream().filter(x -> "PENDING_REVIEW".equals(x.reviewStatus())).count();
            return new Summary(r.id(), r.documentId(), r.laboratoryName(), r.collectedAt(), r.status(),
                    found.results().size(), pending);
        }
    }

    record SummaryPage(List<Summary> items, String nextCursor) {
    }

    record Detail(UUID id, UUID documentId, String laboratoryName, LocalDate collectedAt, String status, int resultCount,
                  int pendingCount, String collectedAtRaw, JsonNode reportWarnings, Instant verifiedAt,
                  List<Result> results) {
    }

    record Result(UUID id, UUID reportId, int sequence, Object analyte, String rawName, Measurement measurement,
                  String unit, Range referenceRange, Flag flag, LocalDate collectedAt, String reviewStatus,
                  JsonNode warnings, Source source) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Measurement(String kind, String rawValue, String valueNumeric, String comparator, String valueText) {
    }

    record Range(String rawText, String low, String high, String appliesTo) {
    }

    record Flag(String value, String source) {
    }

    record Source(UUID documentId, int page, String excerpt) {
    }
}

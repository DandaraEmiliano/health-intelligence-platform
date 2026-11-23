package dev.healthintelligence.core.laboratory;

import java.math.BigDecimal;

import dev.healthintelligence.core.laboratory.LaboratoryReportController.Detail;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Flag;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Measurement;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Range;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Result;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Source;
import dev.healthintelligence.core.laboratory.LaboratoryReportController.Summary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The contract's LaboratoryResult, with provenance, shared by every endpoint that returns results. */
@Component
class ResultViews {

    private final JsonMapper json;
    private final AnalyteCatalog catalog;

    ResultViews(JsonMapper json, AnalyteCatalog catalog) {
        this.json = json;
        this.catalog = catalog;
    }

    Detail detail(LaboratoryReports.ReportWithResults found) {
        LaboratoryReport report = found.report();
        Summary summary = Summary.of(found);
        return new Detail(summary.id(), summary.documentId(), summary.laboratoryName(), summary.collectedAt(),
                summary.status(), summary.resultCount(), summary.pendingCount(), report.collectedAtRaw(),
                json.readTree(report.validationWarnings()), report.verifiedAt(),
                found.results().stream().map(r -> result(report, r)).toList());
    }

    Result result(LaboratoryReport report, LaboratoryResult result) {
        ExtractedValues v = result.values();
        Flag flag = v.labFlag() != null ? new Flag(v.labFlag(), "LABORATORY")
                : v.computedFlag() != null ? new Flag(v.computedFlag(), "COMPUTED") : null;
        JsonNode original = "CORRECTED".equals(result.reviewStatus())
                ? json.readTree(result.extractedSnapshot()).get("measurement") : null;
        AnalyteController.AnalyteView analyte = catalog.find(result.analyteId()).map(AnalyteController.AnalyteView::of).orElse(null);
        return new Result(result.id(), report.id(), v.sequence(), analyte, v.rawName(),
                new Measurement(v.valueKind(), v.valueRaw(), plain(v.valueNumeric()), v.valueComparator(), v.valueText()),
                v.unitRaw(), new Range(v.refRangeText(), plain(v.refLow()), plain(v.refHigh()), v.refAppliesTo()),
                flag, result.collectedAt(), result.reviewStatus(), original, json.readTree(result.validationWarnings()),
                new Source(report.documentId(), v.sourcePage(), v.sourceExcerpt()));
    }

    /** numeric(20,6) comes back as 206.000000; the API shows the decimal without the padding. */
    static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}

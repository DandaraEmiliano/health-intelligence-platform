package dev.healthintelligence.core.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.healthintelligence.core.laboratory.VerifiedResults;
import dev.healthintelligence.core.laboratory.VerifiedResults.AnalyteInfo;
import dev.healthintelligence.core.laboratory.VerifiedResults.Point;
import dev.healthintelligence.core.patient.CurrentPatient;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How each analyte evolved, from verified results only. One series per unit: values in different
 * units are never converted or mixed (ADR 0007). Nothing here is a diagnosis.
 */
@RestController
@RequestMapping("/api/v1/health-analytics/analytes")
class AnalyticsController {

    static final int RECENT_POINTS = 12;

    private final CurrentPatient currentPatient;
    private final VerifiedResults verified;

    AnalyticsController(CurrentPatient currentPatient, VerifiedResults verified) {
        this.currentPatient = currentPatient;
        this.verified = verified;
    }

    @GetMapping
    Overview listTrackedAnalytes() {
        Map<UUID, List<Point>> byAnalyte = verified.points(currentPatient.scope().patient(), null, null, null).stream()
                .filter(p -> p.analyte() != null)
                .collect(Collectors.groupingBy(p -> p.analyte().id(), LinkedHashMap::new, Collectors.toList()));
        List<Tracked> items = new ArrayList<>();
        byAnalyte.values().forEach(points -> {
            Point latest = points.getLast();
            List<Recent> recent = points.stream()
                    .filter(p -> p.value() != null && Objects.equals(p.unitNormalized(), latest.unitNormalized()))
                    .map(p -> new Recent(p.collectedAt(), plain(p.value())))
                    .toList();
            items.add(new Tracked(latest.analyte(), PointView.of(latest), points.size(),
                    recent.subList(Math.max(0, recent.size() - RECENT_POINTS), recent.size())));
        });
        items.sort(Comparator.comparing((Tracked t) -> t.analyte().category()).thenComparing(t -> t.analyte().displayName()));
        return new Overview(items);
    }

    @GetMapping("/{analyteId}/series")
    Series getAnalyteSeries(@PathVariable("analyteId") UUID analyteId,
                            @RequestParam(name = "from", required = false) LocalDate from,
                            @RequestParam(name = "to", required = false) LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ProblemException.validation(List.of(new FieldViolation("from", "INVALID_PERIOD", "from must not be after to")));
        }
        AnalyteInfo analyte = verified.analyte(analyteId).orElseThrow(() -> ProblemException.notFound("ANALYTE_NOT_FOUND"));
        Map<String, List<Point>> byUnit = verified.points(currentPatient.scope().patient(), analyteId, from, to).stream()
                .filter(p -> p.value() != null)
                .collect(Collectors.groupingBy(p -> Objects.requireNonNullElse(p.unitNormalized(), ""), LinkedHashMap::new,
                        Collectors.toList()));
        List<UnitSeries> series = byUnit.values().stream()
                .map(points -> new UnitSeries(points.getFirst().unit(), points.stream().map(PointView::of).toList()))
                .sorted(Comparator.comparing((UnitSeries s) -> s.points().size()).reversed())
                .toList();
        return new Series(analyte, series);
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    record Overview(List<Tracked> items) {
    }

    record Tracked(AnalyteInfo analyte, PointView latest, int count, List<Recent> recent) {
    }

    record Recent(LocalDate collectedAt, String value) {
    }

    record Series(AnalyteInfo analyte, List<UnitSeries> series) {
    }

    record UnitSeries(String unit, List<PointView> points) {
    }

    record Range(String rawText, String low, String high, String appliesTo) {
    }

    record Flag(String value, String source) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PointView(UUID resultId, UUID reportId, LocalDate collectedAt, String value, String comparator, String valueText,
                     String unit, Range referenceRange, Flag flag, String laboratoryName) {

        static PointView of(Point p) {
            return new PointView(p.resultId(), p.reportId(), p.collectedAt(), plain(p.value()), p.comparator(),
                    p.valueText(), p.unit(), new Range(p.rangeText(), plain(p.low()), plain(p.high()), p.appliesTo()),
                    p.flag() == null ? null : new Flag(p.flag(), p.flagSource()), p.laboratoryName());
        }
    }
}

package dev.healthintelligence.core.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.laboratory.VerifiedResults.AnalyteInfo;
import dev.healthintelligence.core.laboratory.VerifiedResults.Point;
import org.junit.jupiter.api.Test;

class FactPackBuilderTests {

    static final AnalyteInfo GLUCOSE = new AnalyteInfo(UUID.randomUUID(), "GLUCOSE", "Glicose", "Bioquímica");
    static final AnalyteInfo FERRITIN = new AnalyteInfo(UUID.randomUUID(), "FERRITIN", "Ferritina", "Hematologia");

    @Test
    void everythingFitsSoEverythingIsSent() {
        List<Point> points = List.of(point(GLUCOSE, LocalDate.of(2020, 1, 1)), point(FERRITIN, LocalDate.of(2021, 1, 1)));

        assertThat(FactPackBuilder.select(points, "qualquer coisa")).isEqualTo(points);
    }

    @Test
    void beyondTheLimitAnalytesNamedInTheQuestionComeFirstThenTheMostRecent() {
        List<Point> points = new ArrayList<>();
        LocalDate start = LocalDate.of(2000, 1, 1);
        Point oldFerritin = point(FERRITIN, start);
        points.add(oldFerritin);
        for (int i = 1; i <= 400; i++) {
            points.add(point(GLUCOSE, start.plusDays(i)));
        }

        List<Point> selected = FactPackBuilder.select(points, "Minha FERRITINA está baixa?");

        assertThat(selected).hasSize(FactPackBuilder.MAX_RESULTS);
        assertThat(selected.getFirst()).isEqualTo(oldFerritin);
        assertThat(selected.getLast().collectedAt()).isEqualTo(start.plusDays(400));
        assertThat(selected.get(1).collectedAt()).isEqualTo(start.plusDays(400 - FactPackBuilder.MAX_RESULTS + 2));
        assertThat(selected).isSortedAccordingTo(java.util.Comparator.comparing(Point::collectedAt));
    }

    @Test
    void aNameInsideAnotherWordIsNotAMention() {
        List<Point> points = new ArrayList<>();
        Point oldFerritin = point(FERRITIN, LocalDate.of(2000, 1, 1));
        points.add(oldFerritin);
        for (int i = 1; i <= 400; i++) {
            points.add(point(GLUCOSE, LocalDate.of(2000, 1, 1).plusDays(i)));
        }

        assertThat(FactPackBuilder.select(points, "superferritinas")).doesNotContain(oldFerritin);
    }

    @Test
    void factsCarryTheValueAsPrintedAndPlainDecimals() {
        Point p = new Point(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 3, 10), "NUMERIC",
                new BigDecimal("12.40"), "<", null, "ng/mL", "ng/mL", "15 a 150", new BigDecimal("15.0"),
                new BigDecimal("1.5E+2"), null, "LOW", "LABORATORY", "Lab Sintético", FERRITIN, "FERRITINA",
                "< 12,4");

        AiServiceClient.ResultFact fact = FactPackBuilder.fact(p);

        assertThat(fact.label()).isEqualTo("Ferritina");
        assertThat(fact.displayValue()).isEqualTo("< 12,4");
        assertThat(fact.valueNumeric()).isEqualTo("12.4");
        assertThat(fact.low()).isEqualTo("15");
        assertThat(fact.high()).isEqualTo("150");
        assertThat(fact.comparator()).isEqualTo("<");
        assertThat(fact.analyteCode()).isEqualTo("FERRITIN");
    }

    private static Point point(AnalyteInfo analyte, LocalDate collectedAt) {
        return new Point(UUID.randomUUID(), UUID.randomUUID(), collectedAt, "NUMERIC", BigDecimal.ONE, null, null,
                "mg/dL", "mg/dL", null, null, null, null, null, null, null, analyte, analyte.displayName(), "1");
    }
}

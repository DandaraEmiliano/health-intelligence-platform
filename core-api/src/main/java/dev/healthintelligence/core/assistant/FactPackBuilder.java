package dev.healthintelligence.core.assistant;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.healthintelligence.core.assistant.AiServiceClient.AllergyFact;
import dev.healthintelligence.core.assistant.AiServiceClient.ConditionFact;
import dev.healthintelligence.core.assistant.AiServiceClient.FactPack;
import dev.healthintelligence.core.assistant.AiServiceClient.MedicationFact;
import dev.healthintelligence.core.assistant.AiServiceClient.PatientInfo;
import dev.healthintelligence.core.assistant.AiServiceClient.ResultFact;
import dev.healthintelligence.core.healthrecords.DeclaredHealth;
import dev.healthintelligence.core.laboratory.VerifiedResults;
import dev.healthintelligence.core.laboratory.VerifiedResults.Point;
import dev.healthintelligence.core.patient.PatientDirectory;
import dev.healthintelligence.core.shared.PatientScope;
import org.springframework.stereotype.Component;

/**
 * Builds the fact pack for one question (ADR 0004). Results come only from
 * {@link VerifiedResults}: values the patient kept (VERIFIED or CORRECTED) in a finished review.
 *
 * <p>Selection rule: every verified result while they fit in {@link #MAX_RESULTS}. Beyond that,
 * results of analytes named in the question come first, then the most recent ones. The pack is
 * sent oldest first.
 */
@Component
class FactPackBuilder {

    static final int MAX_RESULTS = 300;
    static final int MAX_RECORDS = 200;

    /** The pack plus each result by fact id, to turn cited ids back into patient-facing facts. */
    record Built(FactPack pack, Map<UUID, Point> byFactId) {
    }

    private final VerifiedResults verified;
    private final DeclaredHealth declared;
    private final PatientDirectory patients;

    FactPackBuilder(VerifiedResults verified, DeclaredHealth declared, PatientDirectory patients) {
        this.verified = verified;
        this.declared = declared;
        this.patients = patients;
    }

    Built build(PatientScope scope, String question) {
        List<Point> selected = select(verified.points(scope.patient(), null, null, null), question);
        DeclaredHealth.Summary health = declared.of(scope.patient());
        PatientInfo patient = patients.demographics(scope.user())
                .map(d -> new PatientInfo(d.ageYears(), d.biologicalSex() == null ? null : d.biologicalSex().name()))
                .orElse(null);
        FactPack pack = new FactPack(patient,
                selected.stream().map(FactPackBuilder::fact).toList(),
                health.allergies().stream().limit(MAX_RECORDS)
                        .map(a -> new AllergyFact(a.substance(), a.type(), a.severity())).toList(),
                health.medications().stream().limit(MAX_RECORDS)
                        .map(m -> new MedicationFact(m.name(), m.dosageText(), m.frequencyText(), m.active())).toList(),
                health.conditions().stream().limit(MAX_RECORDS)
                        .map(c -> new ConditionFact(c.description(), c.status())).toList());
        return new Built(pack, selected.stream().collect(Collectors.toMap(Point::resultId, Function.identity())));
    }

    /** Input and output oldest first. */
    static List<Point> select(List<Point> points, String question) {
        if (points.size() <= MAX_RESULTS) {
            return points;
        }
        String asked = normalize(question);
        List<Point> ranked = new ArrayList<>(points);
        ranked.sort(Comparator.comparing((Point p) -> !mentioned(p, asked))
                .thenComparing(Point::collectedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Point> kept = new ArrayList<>(ranked.subList(0, MAX_RESULTS));
        kept.sort(Comparator.comparing(Point::collectedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        return kept;
    }

    private static boolean mentioned(Point point, String askedNormalized) {
        if (point.analyte() != null && containsWord(askedNormalized, normalize(point.analyte().displayName()))) {
            return true;
        }
        return point.rawName() != null && containsWord(askedNormalized, normalize(point.rawName()));
    }

    private static boolean containsWord(String text, String name) {
        if (name.length() < 3) {
            return false;
        }
        int at = text.indexOf(name);
        while (at >= 0) {
            boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + name.length();
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            at = text.indexOf(name, at + 1);
        }
        return false;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    static ResultFact fact(Point p) {
        boolean numeric = "NUMERIC".equals(p.kind());
        return new ResultFact(
                p.resultId().toString(),
                p.analyte() == null ? null : p.analyte().code(),
                cut(label(p), 200),
                p.collectedAt() == null ? null : p.collectedAt().toString(),
                numeric ? "NUMERIC" : "TEXT",
                cut(displayValue(p), 200),
                numeric ? plain(p.value()) : null,
                numeric ? p.comparator() : null,
                cut(p.unit(), 50),
                cut(p.rangeText(), 1000),
                plain(p.low()),
                plain(p.high()),
                p.flag(),
                p.flagSource(),
                cut(p.laboratoryName(), 200));
    }

    static String label(Point p) {
        return p.analyte() != null ? p.analyte().displayName() : p.rawName();
    }

    /** As printed (or as the patient corrected it); falls back to the parsed value. */
    static String displayValue(Point p) {
        if (p.valueRaw() != null && !p.valueRaw().isBlank()) {
            return p.valueRaw().strip();
        }
        if ("NUMERIC".equals(p.kind())) {
            return (p.comparator() == null ? "" : p.comparator() + " ") + plain(p.value());
        }
        return p.valueText();
    }

    static String plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        String text = (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
        return text.equals("-0") ? "0" : text;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}

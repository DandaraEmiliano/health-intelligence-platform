package dev.healthintelligence.core.laboratory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import dev.healthintelligence.core.support.Pdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AnalyticsTimelineTests extends ReportTestSupport {

    static final String DELTA = "../synthetic-data/fixtures/lab-reports/SP-001-2023-03-15-delta.expected.json";

    @Autowired AnalyteCatalog catalog;

    @Test
    void everyNamePrintedInTheSyntheticReportsIsRecognised() throws Exception {
        int checked = 0;
        try (var files = Files.list(Path.of("../synthetic-data/fixtures/lab-reports"))) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".expected.json"))::iterator) {
                JsonNode fixture = json.readTree(Files.readString(file));
                JsonNode report = fixture.get("expected").get("report");
                if (report == null) {
                    continue;
                }
                for (JsonNode result : report.get("results")) {
                    String code = fixture.get("analyteCodes").get(String.valueOf(result.get("sequence").intValue())).stringValue();
                    assertThat(catalog.recognise(result.get("rawName").stringValue()).map(Analyte::code))
                            .as(result.get("rawName").stringValue()).hasValue(code);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(300);
        assertThat(catalog.recognise("Teste que não existe")).isEmpty();
    }

    @Test
    void analytesCanBeSearched() {
        JsonNode items = json.readTree(api().get("/api/v1/analytes?query=glic", token).body()).get("items");

        assertThat(items.valueStream().map(i -> i.get("code").stringValue())).contains("GLUCOSE", "HBA1C");
        assertThat(api().get("/api/v1/analytes?query=g", token).statusCode()).isEqualTo(400);
    }

    @Test
    void onlyFinishedReviewsReachTheTimelineAndTheSeries() throws Exception {
        String reportId = ingest(result("SUCCEEDED"));
        String cholesterol = analyteId("CHOL_TOTAL");

        assertThat(items("/api/v1/health-timeline?types=LABORATORY_REPORT")).isEmpty();
        assertThat(json.readTree(api().get("/api/v1/health-analytics/analytes/" + cholesterol + "/series", token).body())
                .get("series").isEmpty()).isTrue();

        finishReview(reportId);

        JsonNode timeline = items("/api/v1/health-timeline?types=LABORATORY_REPORT");
        assertThat(timeline.size()).isEqualTo(1);
        assertThat(timeline.get(0).get("occurredOn").stringValue()).isEqualTo("2022-03-16");
        assertThat(timeline.get(0).get("outOfRangeCount").intValue()).isPositive();
        JsonNode series = json.readTree(api().get("/api/v1/health-analytics/analytes/" + cholesterol + "/series", token).body());
        assertThat(series.get("analyte").get("code").stringValue()).isEqualTo("CHOL_TOTAL");
        JsonNode point = series.get("series").get(0).get("points").get(0);
        assertThat(point.get("value").stringValue()).isEqualTo("206");
        assertThat(point.get("flag").get("source").stringValue()).isEqualTo("LABORATORY");

        JsonNode overview = items("/api/v1/health-analytics/analytes");
        assertThat(overview.valueStream().map(i -> i.get("analyte").get("code").stringValue())).contains("CHOL_TOTAL", "GLUCOSE", "WBC");

        JsonNode report = json.readTree(api().get("/api/v1/laboratory-reports/" + reportId, token).body());
        assertThat(report.get("results").get(0).get("analyte").get("code").stringValue()).isEqualTo("CHOL_TOTAL");
    }

    @Test
    void differentUnitsAreSeparateSeriesNeverConverted() throws Exception {
        finishReview(ingest(result("SUCCEEDED"))); // beta: glucose in mg/dL
        String secondDocument = json.readTree(api().upload("/api/v1/documents", token, "delta.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        String secondJob = single("select id::text from core.extraction_job where document_id = ?::uuid", secondDocument);
        ObjectNode delta = result("SUCCEEDED");
        delta.put("documentId", secondDocument);
        delta.put("jobId", secondJob);
        delta.set("report", json.readTree(Files.readString(Path.of(DELTA))).get("expected").get("report"));
        finishReview(ingest(delta)); // delta: glucose in mmol/L

        JsonNode series = json.readTree(api().get("/api/v1/health-analytics/analytes/" + analyteId("GLUCOSE") + "/series", token).body())
                .get("series");

        assertThat(series.valueStream().map(s -> s.get("unit").stringValue())).containsExactlyInAnyOrder("mg/dL", "mmol/L");
        assertThat(items("/api/v1/health-timeline?types=LABORATORY_REPORT").valueStream().map(i -> i.get("occurredOn").stringValue()))
                .containsExactly("2023-03-15", "2022-03-16");
        assertThat(items("/api/v1/health-timeline").size()).isEqualTo(4); // two reports and their two documents
    }

    @Test
    void anotherPatientSeesNothing() throws Exception {
        finishReview(ingest(result("SUCCEEDED")));
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1991-01-01\"}");

        assertThat(json.readTree(api().get("/api/v1/health-timeline", other).body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(api().get("/api/v1/health-analytics/analytes", other).body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(api().get("/api/v1/health-analytics/analytes/" + analyteId("CHOL_TOTAL") + "/series", other).body())
                .get("series").isEmpty()).isTrue();
    }

    private final Map<String, String> analyteIds = new HashMap<>();

    private String analyteId(String code) {
        return analyteIds.computeIfAbsent(code, c -> {
            try {
                return single("select id::text from core.analyte where code = ?", c);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private String ingest(ObjectNode message) throws Exception {
        String document = message.get("documentId").stringValue();
        publish(message);
        await(() -> json.readTree(api().get("/api/v1/documents/" + document, token).body()),
                d -> d.get("status").stringValue().equals("PROCESSED"));
        return single("select id::text from core.laboratory_report where document_id = ?::uuid", document);
    }

    private void finishReview(String reportId) {
        String etag = api().get("/api/v1/laboratory-reports/" + reportId, token).headers().firstValue("ETag").orElseThrow();
        assertThat(api().postJsonWithHeaders("/api/v1/laboratory-reports/" + reportId + "/review", token,
                "{\"decisions\":[],\"confirmAllRemaining\":true,\"finalize\":true}", "If-Match", etag).statusCode()).isEqualTo(200);
    }

    private JsonNode items(String path) {
        return json.readTree(api().get(path, token).body()).get("items");
    }
}

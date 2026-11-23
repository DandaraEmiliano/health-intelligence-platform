package dev.healthintelligence.core.laboratory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import dev.healthintelligence.core.support.Pdfs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Verified results, out-of-range, summary and comparison (ADR 0006, ADR 0007). */
class VerifiedResultsTests extends ReportTestSupport {

    static final String DELTA = "../synthetic-data/fixtures/lab-reports/SP-001-2023-03-15-delta.expected.json";

    @Test
    void onlyFinishedReviewsAreListedNewestFirst() throws Exception {
        String beta = ingest(result("SUCCEEDED"));
        assertThat(items("/api/v1/laboratory-results").isEmpty()).isTrue();
        assertThat(items("/api/v1/health-analytics/out-of-range").isEmpty()).isTrue();

        finishReview(beta);
        String delta = secondReport();

        JsonNode all = items("/api/v1/laboratory-results?limit=100");
        assertThat(all.size()).isEqualTo(26);
        assertThat(all.get(0).get("collectedAt").stringValue()).isEqualTo("2023-03-15");
        assertThat(all.get(all.size() - 1).get("collectedAt").stringValue()).isEqualTo("2022-03-16");
        assertThat(all.get(0).get("reportId").stringValue()).isEqualTo(delta);
        assertThat(all.get(0).get("source").get("excerpt").isMissingNode()).isFalse();

        JsonNode page = json.readTree(api().get("/api/v1/laboratory-results?limit=20", token).body());
        JsonNode rest = json.readTree(api().get("/api/v1/laboratory-results?limit=20&cursor="
                + page.get("nextCursor").stringValue(), token).body());
        assertThat(page.get("items").size() + rest.get("items").size()).isEqualTo(26);
        assertThat(rest.get("nextCursor").isNull()).isTrue();

        assertThat(items("/api/v1/laboratory-results?from=2023-01-01").size()).isEqualTo(12);
        assertThat(api().get("/api/v1/laboratory-results?from=2023-01-01&to=2022-01-01", token).statusCode()).isEqualTo(400);
        assertThat(api().get("/api/v1/laboratory-results?cursor=@@", token).statusCode()).isEqualTo(400);

        String id = all.get(0).get("id").stringValue();
        assertThat(json.readTree(api().get("/api/v1/laboratory-results/" + id, token).body()).get("id").stringValue()).isEqualTo(id);
    }

    @Test
    void outOfRangeUsesTheLaboratoryFlagFirst() throws Exception {
        finishReview(ingest(result("SUCCEEDED")));

        JsonNode flagged = items("/api/v1/health-analytics/out-of-range");

        assertThat(flagged).isNotEmpty();
        assertThat(flagged.valueStream().map(r -> r.get("flag").get("value").stringValue()))
                .allMatch(v -> v.equals("HIGH") || v.equals("LOW") || v.equals("ABNORMAL") || v.equals("CRITICAL"));
        JsonNode cholesterol = flagged.valueStream()
                .filter(r -> r.get("analyte").get("code").stringValue().equals("CHOL_TOTAL")).findFirst().orElseThrow();
        assertThat(cholesterol.get("flag").get("source").stringValue()).isEqualTo("LABORATORY");
    }

    @Test
    void summaryCountsReportsResultsAndPendingReviews() throws Exception {
        String beta = ingest(result("SUCCEEDED"));
        JsonNode before = summary();
        assertThat(before.get("reportCount").intValue()).isEqualTo(1);
        assertThat(before.get("pendingReviewCount").intValue()).isEqualTo(1);
        assertThat(before.get("verifiedResultCount").intValue()).isZero();
        assertThat(before.get("firstCollectedAt").isNull()).isTrue();

        finishReview(beta);
        secondReport();
        JsonNode after = summary();

        assertThat(after.get("reportCount").intValue()).isEqualTo(2);
        assertThat(after.get("pendingReviewCount").intValue()).isZero();
        assertThat(after.get("verifiedResultCount").intValue()).isEqualTo(26);
        assertThat(after.get("firstCollectedAt").stringValue()).isEqualTo("2022-03-16");
        assertThat(after.get("lastCollectedAt").stringValue()).isEqualTo("2023-03-15");
        assertThat(after.get("mostFrequentAnalytes").size()).isBetween(1, 10);
        assertThat(after.get("mostFrequentAnalytes").valueStream().map(a -> a.get("code").stringValue())).contains("CHOL_TOTAL");
        assertThat(after.get("recentOutOfRange").get(0).get("collectedAt").stringValue()).isEqualTo("2023-03-15");
    }

    @Test
    void comparisonComputesADifferenceOnlyForTheSameUnit() throws Exception {
        String beta = ingest(result("SUCCEEDED"));
        finishReview(beta);
        String delta = secondReport();

        JsonNode comparison = json.readTree(api().get("/api/v1/laboratory-reports/comparison?left=" + beta + "&right=" + delta, token).body());

        assertThat(comparison.get("left").get("id").stringValue()).isEqualTo(beta);
        assertThat(comparison.get("right").get("collectedAt").stringValue()).isEqualTo("2023-03-15");
        JsonNode cholesterol = row(comparison, "CHOL_TOTAL");
        assertThat(cholesterol.get("delta").stringValue()).isEqualTo("0");
        assertThat(cholesterol.get("deltaUnavailableReason").isNull()).isTrue();
        JsonNode glucose = row(comparison, "GLUCOSE");
        assertThat(glucose.get("delta").isNull()).isTrue();
        assertThat(glucose.get("deltaUnavailableReason").stringValue()).isEqualTo("UNIT_MISMATCH");
        assertThat(glucose.get("left").get("unit").stringValue()).isEqualTo("mg/dL");
        assertThat(glucose.get("right").get("unit").stringValue()).isEqualTo("mmol/L");
        assertThat(comparison.get("rows").valueStream().map(r -> r.get("deltaUnavailableReason").stringValue(null)))
                .contains("MISSING_SIDE");
    }

    @Test
    void comparisonNeedsTwoDifferentFinishedReviewsOfThePatient() throws Exception {
        String beta = ingest(result("SUCCEEDED"));
        String pending = secondReportPending();
        finishReview(beta);

        assertThat(compare(beta, beta).statusCode()).isEqualTo(400);
        HttpCheck notVerified = new HttpCheck(compare(beta, pending));
        assertThat(notVerified.status).isEqualTo(400);
        assertThat(notVerified.body).contains("NOT_VERIFIED");
        assertThat(compare(beta, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(api().get("/api/v1/laboratory-reports/comparison?left=" + beta, token).statusCode()).isEqualTo(400);
    }

    /** ADR 0011: another patient sees none of it. */
    @Test
    void anotherPatientSeesNothing() throws Exception {
        String beta = ingest(result("SUCCEEDED"));
        finishReview(beta);
        String delta = secondReport();
        String resultId = items("/api/v1/laboratory-results").get(0).get("id").stringValue();
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1991-01-01\"}");

        assertThat(json.readTree(api().get("/api/v1/laboratory-results", other).body()).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(api().get("/api/v1/health-analytics/out-of-range", other).body()).get("items").isEmpty()).isTrue();
        assertThat(api().get("/api/v1/laboratory-results/" + resultId, other).statusCode()).isEqualTo(404);
        assertThat(api().get("/api/v1/laboratory-reports/comparison?left=" + beta + "&right=" + delta, other).statusCode()).isEqualTo(404);
        JsonNode summary = json.readTree(api().get("/api/v1/health-analytics/summary", other).body());
        assertThat(summary.get("reportCount").intValue()).isZero();
        assertThat(summary.get("verifiedResultCount").intValue()).isZero();
    }

    private record HttpCheck(int status, String body) {
        HttpCheck(java.net.http.HttpResponse<String> response) {
            this(response.statusCode(), response.body());
        }
    }

    private java.net.http.HttpResponse<String> compare(String left, String right) {
        return api().get("/api/v1/laboratory-reports/comparison?left=" + left + "&right=" + right, token);
    }

    private static JsonNode row(JsonNode comparison, String code) {
        return comparison.get("rows").valueStream()
                .filter(r -> r.hasNonNull("analyte") && r.get("analyte").get("code").stringValue().equals(code))
                .findFirst().orElseThrow();
    }

    private String secondReport() throws Exception {
        String id = secondReportPending();
        finishReview(id);
        return id;
    }

    private String secondReportPending() throws Exception {
        String document = json.readTree(api().upload("/api/v1/documents", token, "delta.pdf", Pdfs.synthetic()).body())
                .get("id").stringValue();
        ObjectNode delta = result("SUCCEEDED");
        delta.put("documentId", document);
        delta.put("jobId", single("select id::text from core.extraction_job where document_id = ?::uuid", document));
        delta.set("report", json.readTree(Files.readString(Path.of(DELTA))).get("expected").get("report"));
        return ingest(delta);
    }

    private JsonNode summary() {
        return json.readTree(api().get("/api/v1/health-analytics/summary", token).body());
    }

    private JsonNode items(String path) {
        return json.readTree(api().get(path, token).body()).get("items");
    }
}

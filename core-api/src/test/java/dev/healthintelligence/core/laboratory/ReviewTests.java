package dev.healthintelligence.core.laboratory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReviewTests extends ReportTestSupport {

    String reportId;
    JsonNode detail;
    String etag;

    @BeforeEach
    void extractedReport() throws Exception {
        publish(result("SUCCEEDED"));
        JsonNode list = await(() -> json.readTree(api().get("/api/v1/laboratory-reports", token).body()), p -> p.get("items").size() == 1);
        reportId = list.get("items").get(0).get("id").stringValue();
        reload();
    }

    void reload() {
        HttpResponse<String> response = api().get("/api/v1/laboratory-reports/" + reportId, token);
        detail = json.readTree(response.body());
        etag = response.headers().firstValue("ETag").orElseThrow();
    }

    HttpResponse<String> review(String body) {
        return review(etag, body);
    }

    HttpResponse<String> review(String ifMatch, String body) {
        HttpResponse<String> response = api().postJsonWithHeaders("/api/v1/laboratory-reports/" + reportId + "/review",
                token, body, ifMatch == null ? new String[0] : new String[] {"If-Match", ifMatch});
        if (response.statusCode() == 200) {
            detail = json.readTree(response.body());
            etag = response.headers().firstValue("ETag").orElseThrow();
        }
        return response;
    }

    String resultId(int index) {
        return detail.get("results").get(index).get("id").stringValue();
    }

    @Test
    void confirmCorrectRejectThenFinalize() {
        HttpResponse<String> first = review("""
                {"decisions":[
                  {"resultId":"%s","action":"CONFIRM"},
                  {"resultId":"%s","action":"CORRECT","correction":{
                     "measurement":{"kind":"NUMERIC","rawValue":"52","valueNumeric":"52"},
                     "unit":"mg/dL","referenceRange":{"rawText":"Desejável: > 40","low":"40","high":null,"appliesTo":null}}},
                  {"resultId":"%s","action":"REJECT"}]}""".formatted(resultId(0), resultId(1), resultId(2)));

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(etag).isEqualTo("\"1\"");
        JsonNode results = detail.get("results");
        assertThat(results.get(0).get("reviewStatus").stringValue()).isEqualTo("VERIFIED");
        JsonNode corrected = results.get(1);
        assertThat(corrected.get("reviewStatus").stringValue()).isEqualTo("CORRECTED");
        assertThat(corrected.get("measurement").get("valueNumeric").stringValue()).isEqualTo("52");
        assertThat(corrected.get("originalExtraction").get("rawValue").stringValue()).isNotEqualTo("52");
        assertThat(corrected.get("flag").get("value").stringValue()).isIn("HIGH", "LOW", "ABNORMAL", "IN_RANGE");
        assertThat(results.get(2).get("reviewStatus").stringValue()).isEqualTo("REJECTED");
        assertThat(detail.get("pendingCount").intValue()).isEqualTo(detail.get("resultCount").intValue() - 3);
        assertThat(detail.get("status").stringValue()).isEqualTo("PENDING_REVIEW");

        HttpResponse<String> incomplete = review("{\"decisions\":[],\"finalize\":true}");
        assertThat(incomplete.statusCode()).isEqualTo(409);
        assertThat(json.readTree(incomplete.body()).get("code").stringValue()).isEqualTo("REVIEW_INCOMPLETE");

        HttpResponse<String> done = review("{\"decisions\":[],\"confirmAllRemaining\":true,\"finalize\":true}");
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(detail.get("status").stringValue()).isEqualTo("VERIFIED");
        assertThat(detail.get("verifiedAt").isNull()).isFalse();
        assertThat(detail.get("pendingCount").intValue()).isZero();

        HttpResponse<String> afterwards = review("{\"decisions\":[{\"resultId\":\"%s\",\"action\":\"REJECT\"}]}".formatted(resultId(0)));
        assertThat(afterwards.statusCode()).isEqualTo(409);
        assertThat(json.readTree(afterwards.body()).get("code").stringValue()).isEqualTo("INVALID_STATE");
    }

    @Test
    void theCorrectionKeepsTheOriginalSnapshot() throws Exception {
        String original = single("select extracted_snapshot::text from core.laboratory_result where id = ?::uuid", resultId(0));

        review("""
                {"decisions":[{"resultId":"%s","action":"CORRECT","correction":{
                   "measurement":{"kind":"NUMERIC","rawValue":"190","valueNumeric":"190"},
                   "unit":"mg/dL","referenceRange":{"rawText":"Desejável: < 190","low":null,"high":"190","appliesTo":null}}}]}"""
                .formatted(resultId(0)));

        assertThat(single("select extracted_snapshot::text from core.laboratory_result where id = ?::uuid", resultId(0))).isEqualTo(original);
        assertThat(single("select value_numeric::text from core.laboratory_result where id = ?::uuid", resultId(0))).isEqualTo("190.000000");
        assertThat(single("select count(*)::text from core.audit_event where resource_id = ?::uuid and action = 'LABORATORY_RESULT_CORRECTED'", resultId(0)))
                .isEqualTo("1");
    }

    @Test
    void rejectingEverythingRejectsTheReport() {
        StringBuilder decisions = new StringBuilder();
        for (JsonNode result : detail.get("results")) {
            decisions.append(decisions.isEmpty() ? "" : ",").append("{\"resultId\":\"%s\",\"action\":\"REJECT\"}".formatted(result.get("id").stringValue()));
        }

        assertThat(review("{\"decisions\":[" + decisions + "],\"finalize\":true}").statusCode()).isEqualTo(200);

        assertThat(detail.get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(detail.get("verifiedAt").isNull()).isTrue();
    }

    @Test
    void reviewNeedsTheCurrentETag() {
        assertThat(review(null, "{\"decisions\":[]}").statusCode()).isEqualTo(428);
        String stale = etag;
        review("{\"decisions\":[{\"resultId\":\"%s\",\"action\":\"CONFIRM\"}]}".formatted(resultId(0)));

        assertThat(review(stale, "{\"decisions\":[{\"resultId\":\"%s\",\"action\":\"REJECT\"}]}".formatted(resultId(0))).statusCode())
                .isEqualTo(412);
        reload();
        assertThat(detail.get("results").get(0).get("reviewStatus").stringValue()).isEqualTo("VERIFIED");
    }

    @Test
    void invalidDecisionsAreRejectedWithoutChangingAnything() {
        HttpResponse<String> response = review("""
                {"decisions":[
                  {"resultId":"00000000-0000-7000-8000-000000000000","action":"CONFIRM"},
                  {"resultId":"%s","action":"CORRECT"},
                  {"resultId":"%s","action":"CONFIRM","correction":{"measurement":{"kind":"TEXT","rawValue":"x","valueText":"x"},"referenceRange":{"rawText":null}}},
                  {"resultId":"%s","action":"CORRECT","correction":{"measurement":{"kind":"NUMERIC","rawValue":"5"},"referenceRange":{"rawText":"10 a 1","low":"10","high":"1"}}}],
                 "collectedAt":"2999-01-01"}""".formatted(resultId(0), resultId(1), resultId(2)));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("errors").valueStream().map(e -> e.get("code").stringValue()))
                .contains("NOT_IN_REPORT", "REQUIRED", "NOT_ALLOWED", "INVALID_FORMAT", "INVALID_PERIOD", "FUTURE_DATE_NOT_ALLOWED");
        reload();
        assertThat(detail.get("pendingCount").intValue()).isEqualTo(detail.get("resultCount").intValue());
        assertThat(etag).isEqualTo("\"0\"");
    }

    @Test
    void theCollectionDateCanBeCorrected() throws Exception {
        review("{\"decisions\":[],\"collectedAt\":\"2022-03-15\"}");

        assertThat(detail.get("collectedAt").stringValue()).isEqualTo("2022-03-15");
        assertThat(single("select count(*)::text from core.laboratory_result where report_id = ?::uuid and collected_at <> date '2022-03-15'", reportId))
                .isEqualTo("0");
    }

    @Test
    void pendingCountAndIsolation() {
        assertThat(json.readTree(api().get("/api/v1/laboratory-reports/pending-review/count", token).body()).get("reports").intValue()).isEqualTo(1);
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1991-01-01\"}");

        HttpResponse<String> response = api().postJsonWithHeaders("/api/v1/laboratory-reports/" + reportId + "/review", other,
                "{\"decisions\":[],\"confirmAllRemaining\":true,\"finalize\":true}", "If-Match", etag);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get("/api/v1/laboratory-reports/pending-review/count", other).body()).get("reports").intValue()).isZero();
    }
}

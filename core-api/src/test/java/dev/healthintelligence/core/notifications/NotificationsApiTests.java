package dev.healthintelligence.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.healthintelligence.core.laboratory.ReportTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class NotificationsApiTests extends ReportTestSupport {

    static final String NOTIFICATIONS = "/api/v1/notifications";

    @Test
    void aReadReportAsksForReviewOnceAndCanBeMarkedRead() throws Exception {
        ObjectNode message = result("SUCCEEDED");
        String reportId = ingest(message);
        publish(message); // redelivery: stored nothing new, so no second notification
        Thread.sleep(1_000);

        JsonNode items = items(NOTIFICATIONS);
        assertThat(items.size()).isEqualTo(1);
        JsonNode notification = items.get(0);
        assertThat(notification.get("type").stringValue()).isEqualTo("REVIEW_PENDING");
        assertThat(notification.get("status").stringValue()).isEqualTo("UNREAD");
        assertThat(notification.get("resource").get("type").stringValue()).isEqualTo("LABORATORY_REPORT");
        assertThat(notification.get("resource").get("id").stringValue()).isEqualTo(reportId);
        assertThat(notification.get("readAt").isNull()).isTrue();
        // Ids and status only, never health data.
        assertThat(notification.propertyNames()).containsExactlyInAnyOrder("id", "type", "status", "resource", "createdAt", "readAt");
        assertThat(notification.get("resource").propertyNames()).containsExactlyInAnyOrder("type", "id");

        String id = notification.get("id").stringValue();
        assertThat(api().postJson(NOTIFICATIONS + "/" + id + "/read", token, "").statusCode()).isEqualTo(204);
        assertThat(api().postJson(NOTIFICATIONS + "/" + id + "/read", token, "").statusCode()).isEqualTo(204);

        assertThat(items(NOTIFICATIONS + "?status=UNREAD").isEmpty()).isTrue();
        JsonNode read = items(NOTIFICATIONS + "?status=READ").get(0);
        assertThat(read.get("status").stringValue()).isEqualTo("READ");
        assertThat(read.get("readAt").isNull()).isFalse();
    }

    @Test
    void aFailedExtractionPointsToTheDocument() throws Exception {
        ObjectNode failed = result("FAILED");
        failed.set("failure", json.readTree("{\"code\":\"PDF_CORRUPTED\",\"retryable\":false}"));
        publish(failed);

        JsonNode notification = await(() -> items(NOTIFICATIONS), i -> !i.isEmpty()).get(0);

        assertThat(notification.get("type").stringValue()).isEqualTo("EXTRACTION_FAILED");
        assertThat(notification.get("resource").get("type").stringValue()).isEqualTo("DOCUMENT");
        assertThat(notification.get("resource").get("id").stringValue()).isEqualTo(documentId);
    }

    @Test
    void aScannedDocumentIsReportedAsUnsupported() throws Exception {
        publish(result("UNSUPPORTED_SCANNED"));

        JsonNode notification = await(() -> items(NOTIFICATIONS), i -> !i.isEmpty()).get(0);

        assertThat(notification.get("type").stringValue()).isEqualTo("DOCUMENT_UNSUPPORTED");
    }

    /** ADR 0011: another account neither sees nor changes these notifications. */
    @Test
    void notificationsOfAnotherAccountAreInvisible() throws Exception {
        ingest(result("SUCCEEDED"));
        String id = items(NOTIFICATIONS).get(0).get("id").stringValue();
        String other = tokens.newPatient();

        assertThat(json.readTree(api().get(NOTIFICATIONS, other).body()).get("items").isEmpty()).isTrue();
        assertThat(api().postJson(NOTIFICATIONS + "/" + id + "/read", other, "").statusCode()).isEqualTo(404);
        assertThat(api().postJson(NOTIFICATIONS + "/" + UUID.randomUUID() + "/read", token, "").statusCode()).isEqualTo(404);
        assertThat(items(NOTIFICATIONS).get(0).get("status").stringValue()).isEqualTo("UNREAD");
    }

    @Test
    void pagesAndRejectsBadLimits() throws Exception {
        ingest(result("SUCCEEDED"));

        assertThat(api().get(NOTIFICATIONS + "?limit=0", token).statusCode()).isEqualTo(400);
        assertThat(api().get(NOTIFICATIONS + "?status=SOMETHING", token).statusCode()).isEqualTo(400);
        JsonNode page = json.readTree(api().get(NOTIFICATIONS + "?limit=1", token).body());
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    private JsonNode items(String path) {
        return json.readTree(api().get(path, token).body()).get("items");
    }
}

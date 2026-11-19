package dev.healthintelligence.core.laboratory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes document-extraction-results. Every message is validated against its contract before
 * use (ADR 0003); one that does not match can never succeed and is dropped. A message is deleted
 * only after its transaction committed, so a crash means redelivery, which the ingestion ignores.
 * Message bodies are never logged (threat model T10).
 */
@Component
@ConditionalOnProperty(name = "hip.extraction.results.listener.enabled", havingValue = "true", matchIfMissing = true)
class ExtractionResultListener {

    static final String SCHEMA = "contracts/events/document-extraction-result.v1.schema.json";

    private static final Logger log = LoggerFactory.getLogger(ExtractionResultListener.class);

    private final SqsClient sqs;
    private final ExtractionResultIngestion ingestion;
    private final JsonMapper json;
    private final Schema schema;
    private final String queueName;
    private volatile String queueUrl;

    ExtractionResultListener(SqsClient sqs, ExtractionResultIngestion ingestion, JsonMapper json,
                             @Value("${hip.extraction.results.queue}") String queueName) throws IOException {
        this.sqs = sqs;
        this.ingestion = ingestion;
        this.json = json;
        this.queueName = queueName;
        try (InputStream in = new ClassPathResource(SCHEMA).getInputStream()) {
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(json.readTree(in));
        }
    }

    @Scheduled(fixedDelayString = "${hip.extraction.results.poll-interval}",
               initialDelayString = "${hip.extraction.results.poll-interval}")
    void poll() {
        try {
            List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl()).maxNumberOfMessages(10)
                    .waitTimeSeconds(1)).messages();
            for (Message message : messages) {
                handle(message);
            }
        } catch (RuntimeException e) {
            log.warn("Extraction results poll failed: {}", e.getClass().getSimpleName());
        }
    }

    void handle(Message message) {
        JsonNode body;
        try {
            body = json.readTree(message.body());
        } catch (JacksonException notJson) {
            drop(message, "not JSON");
            return;
        }
        List<Error> errors = schema.validate(body);
        if (!errors.isEmpty()) {
            drop(message, errors.size() + " contract violations");
            return;
        }
        ExtractionResultIngestion.Outcome outcome = ingestion.ingest(body);
        if (outcome == ExtractionResultIngestion.Outcome.IGNORED) {
            log.info("Extraction result for job {} ignored (unknown, already completed or document deleted)",
                    body.get("jobId").stringValue());
        }
        sqs.deleteMessage(r -> r.queueUrl(queueUrl()).receiptHandle(message.receiptHandle()));
    }

    private void drop(Message message, String reason) {
        log.warn("Dropping extraction result message {}: {}", message.messageId(), reason);
        sqs.deleteMessage(r -> r.queueUrl(queueUrl()).receiptHandle(message.receiptHandle()));
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        }
        return queueUrl;
    }
}

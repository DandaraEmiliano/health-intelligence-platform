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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One document-extraction-result message, however it arrived (SQS or HTTP). It is validated
 * against its contract before use (ADR 0003); one that does not match can never succeed.
 * Message bodies are never logged (threat model T10).
 */
@Component
class ExtractionResults {

    static final String SCHEMA = "contracts/events/document-extraction-result.v1.schema.json";

    private static final Logger log = LoggerFactory.getLogger(ExtractionResults.class);

    enum Outcome { INGESTED, IGNORED, INVALID }

    private final ExtractionResultIngestion ingestion;
    private final JsonMapper json;
    private final Schema schema;

    ExtractionResults(ExtractionResultIngestion ingestion, JsonMapper json) throws IOException {
        this.ingestion = ingestion;
        this.json = json;
        try (InputStream in = new ClassPathResource(SCHEMA).getInputStream()) {
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(json.readTree(in));
        }
    }

    /** Throws only when ingestion itself failed, so the message should be delivered again. */
    Outcome accept(String messageId, String payload) {
        JsonNode body;
        try {
            body = json.readTree(payload);
        } catch (JacksonException notJson) {
            log.warn("Dropping extraction result message {}: not JSON", messageId);
            return Outcome.INVALID;
        }
        List<Error> errors = schema.validate(body);
        if (!errors.isEmpty()) {
            log.warn("Dropping extraction result message {}: {} contract violations", messageId, errors.size());
            return Outcome.INVALID;
        }
        if (ingestion.ingest(body) == ExtractionResultIngestion.Outcome.IGNORED) {
            log.info("Extraction result for job {} ignored (unknown, already completed or document deleted)",
                    body.get("jobId").stringValue());
            return Outcome.IGNORED;
        }
        return Outcome.INGESTED;
    }
}

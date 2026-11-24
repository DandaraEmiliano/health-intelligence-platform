package dev.healthintelligence.core.laboratory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * document-extraction-results delivered by HTTP ({@code hip.messaging.transport=http}), on the
 * internal path that needs the internal token. 202 means taken (ingested or a duplicate); 400 means
 * it can never succeed, so the sender drops it; a 5xx asks the sender to try again.
 */
@RestController
@ConditionalOnProperty(name = "hip.messaging.transport", havingValue = "http")
class ExtractionResultsController {

    private final ExtractionResults results;

    ExtractionResultsController(ExtractionResults results) {
        this.results = results;
    }

    @PostMapping("/internal/v1/queues/document-extraction-results")
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Message-Id", required = false) String messageId,
                                 @RequestBody String payload) {
        return results.accept(messageId == null ? "-" : messageId, payload) == ExtractionResults.Outcome.INVALID
                ? ResponseEntity.badRequest().build()
                : ResponseEntity.accepted().build();
    }
}

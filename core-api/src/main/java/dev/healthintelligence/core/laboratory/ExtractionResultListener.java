package dev.healthintelligence.core.laboratory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Consumes document-extraction-results from SQS. A message is deleted only after its transaction
 * committed (or when it can never succeed), so a crash means redelivery, which the ingestion ignores.
 */
@Component
@ConditionalOnExpression("${hip.extraction.results.listener.enabled:true} and '${hip.messaging.transport:sqs}' == 'sqs'")
class ExtractionResultListener {

    private static final Logger log = LoggerFactory.getLogger(ExtractionResultListener.class);

    private final SqsClient sqs;
    private final ExtractionResults results;
    private final String queueName;
    private volatile String queueUrl;

    ExtractionResultListener(SqsClient sqs, ExtractionResults results,
                             @Value("${hip.extraction.results.queue}") String queueName) {
        this.sqs = sqs;
        this.results = results;
        this.queueName = queueName;
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
        results.accept(message.messageId(), message.body());
        sqs.deleteMessage(r -> r.queueUrl(queueUrl()).receiptHandle(message.receiptHandle()));
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        }
        return queueUrl;
    }
}

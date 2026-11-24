package dev.healthintelligence.core.shared;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/** Outbox messages to SQS queues named by their destination (ADR 0003). */
@Component
@ConditionalOnProperty(name = "hip.messaging.transport", havingValue = "sqs", matchIfMissing = true)
class SqsMessageTransport implements MessageTransport {

    private final SqsClient sqs;
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    SqsMessageTransport(SqsClient sqs) {
        this.sqs = sqs;
    }

    @Override
    public void send(String destination, String eventType, String messageId, String payload) {
        sqs.sendMessage(request -> request
                .queueUrl(queueUrls.computeIfAbsent(destination, name -> sqs.getQueueUrl(r -> r.queueName(name)).queueUrl()))
                .messageBody(payload)
                .messageAttributes(Map.of("eventType", attribute(eventType), "messageId", attribute(messageId))));
    }

    private static MessageAttributeValue attribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}

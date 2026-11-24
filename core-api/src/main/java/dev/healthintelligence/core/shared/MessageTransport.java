package dev.healthintelligence.core.shared;

/**
 * Delivers one outbox message to its destination queue: SQS by default ({@link SqsMessageTransport}),
 * or a direct HTTP call to the consuming service ({@link HttpMessageTransport},
 * {@code hip.messaging.transport=http}) where there is no queue service. Throws when the message was
 * not accepted, so the outbox keeps it and tries again.
 */
public interface MessageTransport {

    void send(String destination, String eventType, String messageId, String payload);
}

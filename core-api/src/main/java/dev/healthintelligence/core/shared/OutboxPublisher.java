package dev.healthintelligence.core.shared;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one batch of outbox rows through the {@link MessageTransport} and marks them published, in one transaction that
 * holds the row locks. Called through the Spring proxy by {@link OutboxRelay}; a call from inside
 * this class would bypass the transaction, and rows would never be marked published.
 * A failed send leaves the row pending
 * and counts the attempt; after {@code max-attempts} it stays for investigation. Only ids are
 * logged, never payloads (threat model T10).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository events;
    private final MessageTransport transport;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    OutboxPublisher(OutboxEventRepository events, MessageTransport transport, Clock clock,
                @Value("${hip.outbox.relay.batch-size}") int batchSize,
                @Value("${hip.outbox.relay.max-attempts}") int maxAttempts) {
        this.events = events;
        this.transport = transport;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Publishes one batch. Returns how many messages were sent. */
    @Transactional
    public int publishPending() {
        int sent = 0;
        for (OutboxEvent event : events.lockPending(batchSize, maxAttempts)) {
            try {
                transport.send(event.destination(), event.eventType(), event.id().toString(), event.payload());
                event.published(Instant.now(clock));
                sent++;
            } catch (RuntimeException e) {
                event.failed();
                log.warn("Outbox event {} not published: {}", event.id(), e.getClass().getSimpleName());
            }
        }
        return sent;
    }
}

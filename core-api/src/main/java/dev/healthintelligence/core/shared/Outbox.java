package dev.healthintelligence.core.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox (ADR 0003): the message is stored with the change, and {@link OutboxPublisher}
 * publishes it after commit. Delivery is at least once, so consumers must be idempotent.
 */
@Component
public class Outbox {

    private final OutboxEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    Outbox(OutboxEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param messageId also the outbox row id, so the published message can be traced back to it
     * @param payload   serialised as the message body; must match its contract in contracts/events
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID messageId, String aggregateType, UUID aggregateId, String eventType, String destination,
                        Object payload) {
        events.save(new OutboxEvent(messageId, aggregateType, aggregateId, eventType, destination,
                json.writeValueAsString(payload), Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
    }
}

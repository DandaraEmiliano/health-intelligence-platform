package dev.healthintelligence.core.shared;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A message waiting to be published, written in the same transaction as the change it announces. */
@Entity
@Table(name = "outbox_event")
class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String destination;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    protected OutboxEvent() {
    }

    OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String destination, String payload,
                Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.destination = destination;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    void published(Instant at) {
        publishedAt = at;
        attempts++;
    }

    void failed() {
        attempts++;
    }

    UUID id() { return id; }
    String eventType() { return eventType; }
    String destination() { return destination; }
    String payload() { return payload; }
}

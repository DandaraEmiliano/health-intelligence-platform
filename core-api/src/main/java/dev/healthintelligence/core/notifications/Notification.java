package dev.healthintelligence.core.notifications;

import java.time.Instant;
import java.util.UUID;

import dev.healthintelligence.core.shared.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One in-app notification for an account. {@code payload} holds the resource ids only. */
@Entity
@Table(name = "notification")
class Notification {

    enum Type { DOCUMENT_PROCESSED, REVIEW_PENDING, EXTRACTION_FAILED, DOCUMENT_UNSUPPORTED }

    enum Status { UNREAD, READ }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UserId userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "dedupe_key", nullable = false, updatable = false)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    Notification(UUID id, UserId userId, Type type, String payload, String dedupeKey, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.payload = payload;
        this.dedupeKey = dedupeKey;
        this.status = Status.UNREAD;
        this.createdAt = createdAt;
    }

    /** Idempotent: a notification already read keeps its first read time. */
    void markRead(Instant now) {
        if (status == Status.UNREAD) {
            status = Status.READ;
            readAt = now;
        }
    }

    UUID id() { return id; }
    Type type() { return type; }
    String payload() { return payload; }
    Status status() { return status; }
    Instant createdAt() { return createdAt; }
    Instant readAt() { return readAt; }
}

package dev.healthintelligence.core.audit;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of the append-only audit trail. The application role cannot update or delete it. */
@Entity
@Immutable
@Table(name = "audit_event")
class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditOutcome outcome;

    @Column(name = "correlation_id")
    private String correlationId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private InetAddress ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String metadata;

    protected AuditEvent() {
    }

    AuditEvent(UUID id, Instant occurredAt, AuditRecord record, String correlationId, InetAddress ipAddress,
               String userAgent, String metadataJson) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actorUserId = record.actorUserId();
        this.actorType = record.actorType();
        this.action = record.action();
        this.resourceType = record.resourceType();
        this.resourceId = record.resourceId();
        this.patientId = record.patientId();
        this.outcome = record.outcome();
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.metadata = metadataJson;
    }
}

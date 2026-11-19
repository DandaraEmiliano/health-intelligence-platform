package dev.healthintelligence.core.consent;

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

/** One grant or revocation. Append-only: the current state is the latest record per type. */
@Entity
@Immutable
@Table(name = "consent_record")
class ConsentRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false)
    private ConsentType consentType;

    @Column(name = "document_version", nullable = false)
    private String documentVersion;

    @Column(nullable = false)
    private boolean granted;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private InetAddress ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    protected ConsentRecord() {
    }

    ConsentRecord(UUID id, UUID userId, ConsentType consentType, String documentVersion, boolean granted,
                  Instant recordedAt, InetAddress ipAddress, String userAgent) {
        this.id = id;
        this.userId = userId;
        this.consentType = consentType;
        this.documentVersion = documentVersion;
        this.granted = granted;
        this.recordedAt = recordedAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    UUID id() {
        return id;
    }

    ConsentType consentType() {
        return consentType;
    }

    String documentVersion() {
        return documentVersion;
    }

    boolean granted() {
        return granted;
    }

    Instant recordedAt() {
        return recordedAt;
    }
}

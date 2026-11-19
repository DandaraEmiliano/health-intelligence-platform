package dev.healthintelligence.core.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What happened, by whom, to what. {@code metadata} holds identifiers, codes and field names only,
 * never clinical content (threat model T10).
 */
public record AuditRecord(
        UUID actorUserId,
        ActorType actorType,
        String action,
        String resourceType,
        UUID resourceId,
        UUID patientId,
        AuditOutcome outcome,
        Map<String, String> metadata) {

    public AuditRecord {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(outcome, "outcome");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** A successful action by a user on their own resource. */
    public static AuditRecord userSuccess(UUID actorUserId, String action, String resourceType, UUID resourceId,
                                          UUID patientId, Map<String, String> metadata) {
        return new AuditRecord(actorUserId, ActorType.USER, action, resourceType, resourceId, patientId,
                AuditOutcome.SUCCESS, metadata);
    }
}

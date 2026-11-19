package dev.healthintelligence.core.audit;

import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Insert-only: no update, delete or read methods are exposed to the application. */
interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);
}

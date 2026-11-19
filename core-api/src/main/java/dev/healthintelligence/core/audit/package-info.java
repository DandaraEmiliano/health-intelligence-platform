/**
 * Append-only audit trail of sensitive operations.
 *
 * <p>Phase 4: {@link dev.healthintelligence.core.audit.AuditLog} writes the append-only trail in the caller's transaction.
 */
@ApplicationModule(displayName = "Audit")
package dev.healthintelligence.core.audit;

import org.springframework.modulith.ApplicationModule;

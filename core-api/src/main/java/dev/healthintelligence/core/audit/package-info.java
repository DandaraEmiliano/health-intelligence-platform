/**
 * Append-only audit trail of sensitive operations.
 *
 * <p>Implementation starts in Phase 4. See docs/adr/0001-modular-monolith-and-separate-ai-service.md.
 */
@ApplicationModule(displayName = "Audit")
package dev.healthintelligence.core.audit;

import org.springframework.modulith.ApplicationModule;

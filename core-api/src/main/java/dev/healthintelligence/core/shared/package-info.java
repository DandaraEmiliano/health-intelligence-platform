/**
 * Cross-cutting types used by every module: identifiers, errors, current-user abstraction, outbox.
 *
 * <p>Open module: its types may be used by every other module.

 * <p>Implementation starts in Phase 4. See docs/adr/0001-modular-monolith-and-separate-ai-service.md.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package dev.healthintelligence.core.shared;

import org.springframework.modulith.ApplicationModule;

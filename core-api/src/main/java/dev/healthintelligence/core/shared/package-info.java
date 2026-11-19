/**
 * Cross-cutting types used by every module: identifiers, errors, current-user abstraction, outbox.
 *
 * <p>Open module: its types may be used by every other module.
 *
 * <p>Phase 4: identifiers, Problem Details errors, ETags, correlation ids, the application clock.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package dev.healthintelligence.core.shared;

import org.springframework.modulith.ApplicationModule;

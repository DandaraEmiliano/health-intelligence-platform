/**
 * Maps the identity provider subject to a local user account; account lifecycle (ADR 0002).
 *
 * <p>Implementation starts in Phase 4. See docs/adr/0001-modular-monolith-and-separate-ai-service.md.
 */
@ApplicationModule(displayName = "Identity")
package dev.healthintelligence.core.identity;

import org.springframework.modulith.ApplicationModule;

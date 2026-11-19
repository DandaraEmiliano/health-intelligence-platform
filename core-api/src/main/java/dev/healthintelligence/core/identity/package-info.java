/**
 * Maps the identity provider subject to a local user account; account lifecycle (ADR 0002).
 *
 * <p>Phase 4: {@link dev.healthintelligence.core.identity.CurrentUser} provisions the local account on first use (just in time) and keeps email and roles in sync with the token.
 */
@ApplicationModule(displayName = "Identity")
package dev.healthintelligence.core.identity;

import org.springframework.modulith.ApplicationModule;

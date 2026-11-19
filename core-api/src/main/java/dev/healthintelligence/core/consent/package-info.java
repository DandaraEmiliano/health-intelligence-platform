/**
 * Versioned, append-only consent records and consent checks.
 *
 * <p>Phase 4: {@code GET/POST /api/v1/consents}. Revoking {@code AI_PROCESSING} will trigger the purge of embeddings in Phase 5.
 */
@ApplicationModule(displayName = "Consent")
package dev.healthintelligence.core.consent;

import org.springframework.modulith.ApplicationModule;

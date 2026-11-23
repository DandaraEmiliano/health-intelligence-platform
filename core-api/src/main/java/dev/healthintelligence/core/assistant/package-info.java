/**
 * Conversations, fact pack and client of ai-service (ADR 0004).
 *
 * <p>The core resolves the patient, checks AI consent and the rate limit, builds a fact pack of
 * verified data only, asks ai-service for a structured answer and stores both turns. ai-service
 * never reads the core schema and keeps no conversation text.
 */
@ApplicationModule(displayName = "AI assistant")
package dev.healthintelligence.core.assistant;

import org.springframework.modulith.ApplicationModule;

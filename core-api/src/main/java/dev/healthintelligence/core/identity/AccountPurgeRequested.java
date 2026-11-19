package dev.healthintelligence.core.identity;

import dev.healthintelligence.core.shared.UserId;

/**
 * Published synchronously by the purge job before the account row is deleted. Modules that keep
 * data outside the database (files, the AI index) remove it in a listener; a listener that throws
 * stops the purge, which is retried on the next run, so listeners must be idempotent.
 */
public record AccountPurgeRequested(UserId user) {
}

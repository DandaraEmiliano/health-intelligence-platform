/**
 * In-app notifications driven by domain events: a document was read, needs review, failed or is
 * not supported. Payloads hold ids only, never health data.
 */
@ApplicationModule(displayName = "Notifications")
package dev.healthintelligence.core.notifications;

import org.springframework.modulith.ApplicationModule;

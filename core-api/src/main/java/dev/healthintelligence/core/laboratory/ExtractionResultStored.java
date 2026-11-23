package dev.healthintelligence.core.laboratory;

import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;

/**
 * Published in the ingestion transaction once an extraction result was stored (not for ignored
 * redeliveries). Carries ids and the outcome only, never health data.
 *
 * @param reportId the report created for a SUCCEEDED result, otherwise null
 * @param outcome  SUCCEEDED, FAILED or UNSUPPORTED_SCANNED
 */
public record ExtractionResultStored(PatientId patient, UUID documentId, UUID reportId, String outcome) {
}

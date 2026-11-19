package dev.healthintelligence.core.documents;

import java.time.Instant;

/** Body of the message on document-extraction-requests (contracts/events/document-extraction-request.v1). */
record ExtractionRequest(
        String schemaVersion,
        String messageId,
        String jobId,
        String documentId,
        String patientId,
        String extractorVersion,
        StoredObject document,
        Instant requestedAt,
        String correlationId) {

    static final String SCHEMA_VERSION = "1.0";

    record StoredObject(String bucket, String objectKey, String sha256, String contentType, long sizeBytes) {
    }
}

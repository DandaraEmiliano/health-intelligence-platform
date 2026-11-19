package dev.healthintelligence.core.documents;

/**
 * UPLOADED: stored, extraction not requested (AI consent not granted). PROCESSING: extraction
 * requested. The other states are set when extraction results arrive (Phase 5).
 */
enum DocumentStatus {
    UPLOADED, PROCESSING, PROCESSED, FAILED, UNSUPPORTED_SCANNED
}

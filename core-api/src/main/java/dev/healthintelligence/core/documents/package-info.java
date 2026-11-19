/**
 * Upload, storage, processing status and secure download of health documents.
 *
 * <p>Phase 4: upload (PDF checks, S3, outbox message for extraction when AI consent is granted),
 * listing with cursor pagination, audited download links and deletion.
 */
@ApplicationModule(displayName = "Documents")
package dev.healthintelligence.core.documents;

import org.springframework.modulith.ApplicationModule;

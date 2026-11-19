package dev.healthintelligence.core.documents;

import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.consent.ConsentType;
import dev.healthintelligence.core.consent.Consents;
import dev.healthintelligence.core.shared.CorrelationIds;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.Outbox;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upload, listing, download and deletion of a patient's documents. The upload follows ADR 0003:
 * the PDF goes to S3 first, then one transaction writes the document, the extraction job and
 * the outbox message, so either all of them exist or none does.
 */
@Service
class DocumentService {

    static final String NOT_FOUND = "DOCUMENT_NOT_FOUND";
    static final String EXTRACTION_QUEUE_EVENT = "DocumentExtractionRequested";

    private final DocumentRepository documents;
    private final ExtractionJobRepository jobs;
    private final DocumentStorage storage;
    private final Consents consents;
    private final Outbox outbox;
    private final AuditLog auditLog;
    private final CorrelationIds correlationIds;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String extractorVersion;
    private final String extractionQueue;

    DocumentService(DocumentRepository documents, ExtractionJobRepository jobs, DocumentStorage storage,
                    Consents consents, Outbox outbox, AuditLog auditLog, CorrelationIds correlationIds,
                    TransactionTemplate transaction, Clock clock,
                    @Value("${hip.extraction.extractor-version}") String extractorVersion,
                    @Value("${hip.extraction.queue}") String extractionQueue) {
        this.documents = documents;
        this.jobs = jobs;
        this.storage = storage;
        this.consents = consents;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.correlationIds = correlationIds;
        this.transaction = transaction;
        this.clock = clock;
        this.extractorVersion = extractorVersion;
        this.extractionQueue = extractionQueue;
    }

    HealthDocument upload(PatientScope scope, String filename, byte[] content, String sha256) {
        PdfInspector.check(content);
        if (documents.existsLiveWithHash(scope.patient(), sha256)) {
            throw ProblemException.conflict("DUPLICATE_DOCUMENT");
        }
        boolean extract = consents.isGranted(scope.user(), ConsentType.AI_PROCESSING);
        UUID id = Ids.newId();
        HealthDocument document = new HealthDocument(id, scope.patient(), PdfInspector.safeFilename(filename),
                content.length, sha256, extract ? DocumentStatus.PROCESSING : DocumentStatus.UPLOADED, now());

        storage.put(document.storageKey(), content);
        try {
            return transaction.execute(status -> {
                HealthDocument saved = documents.saveAndFlush(document);
                if (extract) {
                    requestExtraction(saved);
                }
                auditLog.record(AuditRecord.userSuccess(scope.user().value(), "DOCUMENT_UPLOADED", "DOCUMENT",
                        saved.id(), scope.patient().value(), Map.of("extractionRequested", String.valueOf(extract))));
                return saved;
            });
        } catch (DataIntegrityViolationException sameFileConcurrently) {
            storage.deleteQuietly(document.storageKey());
            throw ProblemException.conflict("DUPLICATE_DOCUMENT");
        } catch (RuntimeException e) {
            storage.deleteQuietly(document.storageKey());
            throw e;
        }
    }

    private void requestExtraction(HealthDocument document) {
        ExtractionJob job = jobs.save(new ExtractionJob(Ids.newId(), document.id(), document.patientId(),
                extractorVersion, document.uploadedAt()));
        UUID messageId = Ids.newId();
        outbox.enqueue(messageId, "DOCUMENT", document.id(), EXTRACTION_QUEUE_EVENT, extractionQueue,
                new ExtractionRequest(ExtractionRequest.SCHEMA_VERSION, messageId.toString(), job.id().toString(),
                        document.id().toString(), document.patientId().value().toString(), extractorVersion,
                        new ExtractionRequest.StoredObject(storage.bucket(), document.storageKey(), document.sha256(),
                                HealthDocument.CONTENT_TYPE, document.sizeBytes()),
                        document.uploadedAt(), correlationIds.current()));
    }

    @Transactional(readOnly = true)
    HealthDocument get(PatientScope scope, UUID id) {
        return documents.findLive(id, scope.patient()).orElseThrow(() -> ProblemException.notFound(NOT_FOUND));
    }

    @Transactional(readOnly = true)
    List<HealthDocument> page(PatientScope scope, DocumentStatus status, DocumentCursor after, int limit) {
        return documents.page(scope.patient(), status, after == null ? null : after.uploadedAt(),
                after == null ? null : after.id(), Limit.of(limit + 1));
    }

    /** Audited: every read of a document's content leaves a trail (threat model T11). */
    record Download(URL url, Instant expiresAt) {
    }

    @Transactional
    Download downloadUrl(PatientScope scope, UUID id) {
        HealthDocument document = get(scope, id);
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), "DOCUMENT_CONTENT_ACCESSED", "DOCUMENT",
                document.id(), scope.patient().value(), Map.of()));
        Instant expiresAt = Instant.now(clock).plus(storage.downloadTtl());
        return new Download(storage.downloadUrl(document.storageKey(), document.originalFilename()), expiresAt);
    }

    /**
     * Hides the document at once and removes the file after commit. Derived results and the AI
     * index are purged by later phases when they exist.
     */
    @Transactional
    void delete(PatientScope scope, UUID id) {
        HealthDocument document = get(scope, id);
        document.delete(now());
        documents.saveAndFlush(document);
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), "DOCUMENT_DELETED", "DOCUMENT",
                document.id(), scope.patient().value(), Map.of()));
        String key = document.storageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.deleteQuietly(key);
            }
        });
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}

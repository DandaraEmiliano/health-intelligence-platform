package dev.healthintelligence.core.documents;

import java.net.URL;
import java.time.Duration;

/**
 * Where PDFs live, by opaque key: S3 ({@link S3DocumentStorage}, the default) or the database
 * ({@link DatabaseDocumentStorage}, {@code hip.documents.store=database}). Only these classes touch
 * the content.
 */
interface DocumentStorage {

    /** The bucket named in extraction requests; a fixed label when the store is the database. */
    String bucket();

    Duration downloadTtl();

    void put(String key, byte[] content);

    /** Deleting a key that does not exist succeeds, so this is safe to repeat. */
    void delete(String key);

    /** Best effort: a leftover object has an opaque key and no row pointing at it. */
    void deleteQuietly(String key);

    /** Short-lived link that opens the PDF inline under its original name (threat model T09). */
    URL downloadUrl(String key, String filename);
}

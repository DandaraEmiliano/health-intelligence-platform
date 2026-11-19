package dev.healthintelligence.core.documents;

import java.net.URL;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** PDFs in S3, by opaque key. Only this class talks to the bucket. */
@Component
class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration downloadTtl;

    DocumentStorage(S3Client s3, S3Presigner presigner, @Value("${hip.documents.bucket}") String bucket,
                    @Value("${hip.documents.download-url-ttl}") Duration downloadTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.downloadTtl = downloadTtl;
    }

    String bucket() {
        return bucket;
    }

    Duration downloadTtl() {
        return downloadTtl;
    }

    void put(String key, byte[] content) {
        s3.putObject(request -> request.bucket(bucket).key(key).contentType(HealthDocument.CONTENT_TYPE)
                .contentLength((long) content.length), RequestBody.fromBytes(content));
    }

    /** Best effort: a leftover object has an opaque key and no row pointing at it. */
    void deleteQuietly(String key) {
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            log.warn("Could not delete stored object {}: {}", key, e.getClass().getSimpleName());
        }
    }

    /** Short-lived link that opens the PDF inline under its original name (threat model T09). */
    URL downloadUrl(String key, String filename) {
        String disposition = "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return presigner.presignGetObject(request -> request
                .signatureDuration(downloadTtl)
                .getObjectRequest(get -> get.bucket(bucket).key(key)
                        .responseContentType(HealthDocument.CONTENT_TYPE)
                        .responseContentDisposition(disposition)
                        .responseCacheControl("no-store")))
                .url();
    }
}

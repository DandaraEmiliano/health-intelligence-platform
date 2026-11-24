package dev.healthintelligence.core.documents;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PDFs in {@code core.document_content}, for deployments without object storage
 * ({@code hip.documents.store=database}). Download links work like S3 pre-signed URLs: they name
 * the object, carry an expiry and an HMAC over both, and need no session (threat model T09).
 */
@Component
@ConditionalOnProperty(name = "hip.documents.store", havingValue = "database")
class DatabaseDocumentStorage implements DocumentStorage {

    static final String BUCKET = "database";
    static final String LINK_PATH = "/api/v1/document-content/";
    private static final String PREFIX = "documents/";
    private static final Logger log = LoggerFactory.getLogger(DatabaseDocumentStorage.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration downloadTtl;
    private final String publicBaseUrl;
    private final SecretKeySpec linkKey;

    DatabaseDocumentStorage(JdbcTemplate jdbc, Clock clock,
                            @Value("${hip.documents.download-url-ttl}") Duration downloadTtl,
                            @Value("${hip.public-base-url}") String publicBaseUrl,
                            @Value("${hip.documents.link-secret:}") String linkSecret) {
        if (linkSecret.length() < 32) {
            throw new IllegalStateException("hip.documents.link-secret must have at least 32 characters");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.downloadTtl = downloadTtl;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.linkKey = new SecretKeySpec(linkSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String bucket() {
        return BUCKET;
    }

    @Override
    public Duration downloadTtl() {
        return downloadTtl;
    }

    @Override
    public void put(String key, byte[] content) {
        jdbc.update("insert into core.document_content (storage_key, content) values (?, ?)", key, content);
    }

    @Override
    public void delete(String key) {
        jdbc.update("delete from core.document_content where storage_key = ?", key);
    }

    @Override
    public void deleteQuietly(String key) {
        try {
            delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored object {}: {}", key, e.getClass().getSimpleName());
        }
    }

    @Override
    public URL downloadUrl(String key, String filename) {
        long expires = Instant.now(clock).plus(downloadTtl).getEpochSecond();
        String object = objectId(key);
        String url = publicBaseUrl + LINK_PATH + object + "?name=" + encode(filename) + "&expires=" + expires
                + "&signature=" + sign(object, filename, expires);
        try {
            return URI.create(url).toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("hip.public-base-url is not a valid URL", e);
        }
    }

    /** The content behind a link, if the link is genuine and still valid. */
    Optional<byte[]> contentForLink(String object, String filename, long expires, String signature) {
        if (Instant.now(clock).getEpochSecond() > expires) {
            return Optional.empty();
        }
        byte[] expected = sign(object, filename, expires).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return content(PREFIX + object);
    }

    /** For ai-service, by key (ADR 0003: by key, never by link). */
    Optional<byte[]> content(String key) {
        List<byte[]> rows = jdbc.query("select content from core.document_content where storage_key = ?",
                (row, n) -> row.getBytes(1), key);
        return rows.stream().findFirst();
    }

    static String objectId(String key) {
        if (!key.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a document key");
        }
        return key.substring(PREFIX.length());
    }

    private String sign(String object, String filename, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(linkKey);
            byte[] digest = mac.doFinal((object + "\n" + filename + "\n" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

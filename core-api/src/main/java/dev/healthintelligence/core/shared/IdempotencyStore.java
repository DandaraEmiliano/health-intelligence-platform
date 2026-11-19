package dev.healthintelligence.core.shared;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} support (ADR 0009). The first request with a key reserves it; its
 * successful response is kept for 24 hours and replayed for retries with the same request. The
 * same key with a different request is rejected. Keys are scoped to the user.
 */
@Component
public class IdempotencyStore {

    private static final String PENDING = "PENDING:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    IdempotencyStore(StringRedisTemplate redis, JsonMapper json, @Value("${hip.idempotency.ttl}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    /** A stored success: status, Location header and body as JSON. */
    public record StoredResponse(String fingerprint, int status, String location, String body) {
    }

    /**
     * Reserves the key, or returns the stored response of an earlier identical request.
     *
     * @param fingerprint digest of what makes the request what it is (for an upload: content and name)
     */
    public Optional<StoredResponse> begin(UserId user, String key, String fingerprint) {
        String redisKey = redisKey(user, key);
        Boolean reserved = redis.opsForValue().setIfAbsent(redisKey, PENDING + fingerprint, ttl);
        if (Boolean.TRUE.equals(reserved)) {
            return Optional.empty();
        }
        String existing = redis.opsForValue().get(redisKey);
        if (existing == null) {
            // Expired between the two calls: try once more.
            return begin(user, key, fingerprint);
        }
        String storedFingerprint = existing.startsWith(PENDING)
                ? existing.substring(PENDING.length())
                : json.readValue(existing, StoredResponse.class).fingerprint();
        if (!storedFingerprint.equals(fingerprint)) {
            throw new ProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key already used for a different request");
        }
        if (existing.startsWith(PENDING)) {
            throw ProblemException.conflict("REQUEST_IN_PROGRESS");
        }
        return Optional.of(json.readValue(existing, StoredResponse.class));
    }

    public void complete(UserId user, String key, StoredResponse response) {
        redis.opsForValue().set(redisKey(user, key), json.writeValueAsString(response), ttl);
    }

    /** Releases the key after a failure, so the client can retry with it. */
    public void abandon(UserId user, String key) {
        redis.delete(redisKey(user, key));
    }

    private static String redisKey(UserId user, String key) {
        return "idempotency:" + user.value() + ":" + key;
    }
}

package dev.healthintelligence.core.assistant;

import java.time.Clock;
import java.time.Duration;

import dev.healthintelligence.core.shared.ProblemException;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Questions per user in a fixed window, counted in Redis so every instance shares the count. Each
 * call to the model costs money and is an abuse vector (threat model), so the limit applies before
 * ai-service is called. Replays of an idempotent request are not counted.
 */
@Component
class AssistantRateLimit {

    static final String CODE = "RATE_LIMITED";

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int maxQuestions;
    private final Duration window;

    AssistantRateLimit(StringRedisTemplate redis, Clock clock,
                       @Value("${hip.assistant.rate-limit.max-questions}") int maxQuestions,
                       @Value("${hip.assistant.rate-limit.window}") Duration window) {
        this.redis = redis;
        this.clock = clock;
        this.maxQuestions = maxQuestions;
        this.window = window;
    }

    /** One counted question; {@link #release()} gives it back. */
    final class Permit {
        private final String key;

        private Permit(String key) {
            this.key = key;
        }

        /** For a question that was not answered (ai-service unavailable). */
        void release() {
            redis.opsForValue().decrement(key);
        }
    }

    /** Counts one question, or fails with 429 and {@code Retry-After} until the window ends. */
    Permit acquire(UserId user) {
        long windowMillis = window.toMillis();
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, windowMillis);
        String key = "assistant:rate:" + user.value() + ":" + windowStart;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window.plusSeconds(5));
        }
        if (count != null && count > maxQuestions) {
            long retryAfter = Math.ceilDiv(windowStart + windowMillis - now, 1000);
            throw ProblemException.retryLater(HttpStatus.TOO_MANY_REQUESTS, CODE, "Too many requests", retryAfter);
        }
        return new Permit(key);
    }
}

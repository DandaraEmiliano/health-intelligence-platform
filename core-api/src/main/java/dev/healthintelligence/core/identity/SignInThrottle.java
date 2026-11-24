package dev.healthintelligence.core.identity;

import java.time.Duration;

import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Failed sign-ins per e-mail in a 15-minute window, counted in Redis. Past the limit every attempt
 * is refused with 429 until the window ends, whether the password is right or not, which stops
 * password guessing (it also lets someone lock an e-mail out for 15 minutes: accepted for now).
 */
@Component
@ConditionalOnProperty(name = "hip.auth.mode", havingValue = "local")
class SignInThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    SignInThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void check(String normalizedEmail) {
        String count = redis.opsForValue().get(key(normalizedEmail));
        if (count != null && Integer.parseInt(count) >= MAX_FAILURES) {
            Long ttl = redis.getExpire(key(normalizedEmail));
            throw ProblemException.retryLater(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts",
                    ttl == null || ttl < 1 ? WINDOW.toSeconds() : ttl);
        }
    }

    void failed(String normalizedEmail) {
        Long count = redis.opsForValue().increment(key(normalizedEmail));
        if (count != null && count == 1) {
            redis.expire(key(normalizedEmail), WINDOW);
        }
    }

    void succeeded(String normalizedEmail) {
        redis.delete(key(normalizedEmail));
    }

    private static String key(String normalizedEmail) {
        return "signin:failures:" + normalizedEmail;
    }
}

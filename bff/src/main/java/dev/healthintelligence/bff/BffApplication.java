package dev.healthintelligence.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend-for-Frontend (ADR 0014): signs the browser in with Keycloak, keeps the tokens in a
 * Redis session behind an HttpOnly cookie, and relays {@code /api/**} to core-api with a bearer
 * token. The browser never sees a token.
 */
@SpringBootApplication
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}

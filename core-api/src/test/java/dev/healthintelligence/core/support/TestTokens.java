package dev.healthintelligence.core.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues RS256 access tokens shaped like the local Keycloak realm's (issuer, audience {@code core-api},
 * {@code roles} and {@code email} claims) and verifies them with the same validation rules.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

    public static final String ISSUER = "http://localhost:1/realms/hip";

    private final KeyPair keys = generateKeys();
    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
            new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate())
                    .keyID("test").build())));

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains("core-api")
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience", null))));
        return decoder;
    }

    @Bean
    TestTokens.Issuer testTokenIssuer() {
        return new Issuer(this);
    }

    String issue(String subject, String email, List<String> roles, String audience) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", roles);
        if (email != null) {
            claims.claim("email", email);
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(), claims.build())).getTokenValue();
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Injectable entry point for tests. */
    public static final class Issuer {

        private final TestTokens tokens;

        Issuer(TestTokens tokens) {
            this.tokens = tokens;
        }

        /** A token for a brand-new synthetic patient (fresh subject and email). */
        public String newPatient() {
            String subject = UUID.randomUUID().toString();
            return patient(subject, "patient-" + subject + "@example.test");
        }

        public String patient(String subject, String email) {
            return tokens.issue(subject, email, List.of("PATIENT"), "core-api");
        }

        public String withRoles(List<String> roles) {
            String subject = UUID.randomUUID().toString();
            return tokens.issue(subject, subject + "@example.test", roles, "core-api");
        }

        public String withoutEmail() {
            return tokens.issue(UUID.randomUUID().toString(), null, List.of("PATIENT"), "core-api");
        }

        public String forAudience(String audience) {
            String subject = UUID.randomUUID().toString();
            return tokens.issue(subject, subject + "@example.test", List.of("PATIENT"), audience);
        }
    }
}

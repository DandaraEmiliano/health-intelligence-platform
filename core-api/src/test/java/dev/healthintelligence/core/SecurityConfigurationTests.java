package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTests {

    @Test
    void mapsRealmRolesToSpringAuthorities() {
        Jwt jwt = jwt(List.of("PATIENT"));

        assertThat(SecurityConfiguration.realmRoles(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PATIENT");
    }

    @Test
    void tokenWithoutRolesClaimGetsNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("synthetic-subject")
                .issuedAt(Instant.parse("2026-11-12T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-12T00:05:00Z"))
                .build();

        assertThat(SecurityConfiguration.realmRoles(jwt)).isEmpty();
    }

    private static Jwt jwt(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("synthetic-subject")
                .claim(SecurityConfiguration.ROLES_CLAIM, roles)
                .issuedAt(Instant.parse("2026-11-12T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-12T00:05:00Z"))
                .build();
    }
}

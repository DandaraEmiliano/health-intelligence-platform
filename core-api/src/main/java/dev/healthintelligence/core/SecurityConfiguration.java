package dev.healthintelligence.core;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import dev.healthintelligence.core.shared.CorrelationIds;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security for the public API (ADR 0002, ADR 0009).
 *
 * <p>core-api is a stateless OAuth 2.0 resource server: it validates Keycloak-issued JWTs
 * (issuer and audience come from configuration) and never handles passwords. Everything that
 * is not explicitly public requires a valid token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String ROLES_CLAIM = "roles";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CorrelationIds correlationIds) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Scraped from the private network only; restricted further in Phase 7.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Present only when springdoc is enabled (local and test profiles).
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // The MVP API serves patients only; ADMIN has no clinical access (ADR 0002).
                        .requestMatchers("/api/**").hasRole("PATIENT")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemEntryPoint(correlationIds))
                        .accessDeniedHandler(problemAccessDeniedHandler(correlationIds)))
                .build();
    }

    /** 401 with the standard {@code WWW-Authenticate} header and a Problem Details body (ADR 0009). */
    static AuthenticationEntryPoint problemEntryPoint(CorrelationIds correlationIds) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            writeProblem(response, "UNAUTHORIZED", "Missing or invalid access token", correlationIds.current());
        };
    }

    /** 403 for authenticated callers without the required role. */
    static AccessDeniedHandler problemAccessDeniedHandler(CorrelationIds correlationIds) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception);
            writeProblem(response, "FORBIDDEN", "Forbidden", correlationIds.current());
        };
    }

    /** Hand-written JSON: every value is a constant or a hex id, so nothing needs escaping. */
    private static void writeProblem(HttpServletResponse response, String code, String title, String correlationId)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://docs.health-intelligence-platform.dev/problems/%s","title":"%s","status":%d,\
                "code":"%s","correlationId":"%s"}""".formatted(
                code.toLowerCase(Locale.ROOT), title, response.getStatus(), code, correlationId));
    }

    /**
     * CORS for the browser client (ADR 0013). Only the configured origins may call the API; there are
     * no cookies, so credentials are not allowed.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${hip.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match"));
        cors.setExposedHeaders(List.of("ETag", "Location", "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        source.registerCorsConfiguration("/actuator/health/**", cors);
        return source;
    }

    /** Maps the realm roles claim ({@code roles: ["PATIENT"]}) to {@code ROLE_PATIENT} authorities. */
    static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::realmRoles);
        return converter;
    }

    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}

package dev.healthintelligence.core;

import java.io.IOException;

import dev.healthintelligence.core.shared.CorrelationIds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * HTTP security with the built-in sign-in ({@code hip.auth.mode=local}, the Render profile, where
 * there is no Keycloak and no BFF). core-api then plays the BFF's part of ADR 0014 itself: a
 * server-side session (Spring Session, Redis) behind an HttpOnly {@code __Host-} cookie, and the
 * SPA double-submit CSRF token on every unsafe request. The API rules are those of the JWT chain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hip.auth.mode", havingValue = "local")
class LocalSignInSecurityConfiguration {

    static final String SESSION_COOKIE = "__Host-hip-session";

    @Bean
    SecurityFilterChain localSignInSecurityFilterChain(HttpSecurity http, CorrelationIds correlationIds) throws Exception {
        return http
                .cors(cors -> cors.disable()) // same origin only
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .headers(SecurityConfiguration::apiHeaders)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers("/bff/user", "/bff/login", "/bff/register").permitAll()
                        .requestMatchers(SecurityConfiguration.DOCUMENT_LINKS + "**").permitAll()
                        .requestMatchers("/api/**").hasRole("PATIENT")
                        .requestMatchers("/internal/**", "/v3/api-docs/**", "/swagger-ui/**").denyAll()
                        // The single-page app's files (part of the image in the Render profile).
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(SecurityConfiguration.sessionEntryPoint(correlationIds))
                        .accessDeniedHandler(SecurityConfiguration.sessionAccessDeniedHandler(correlationIds)))
                // The SPA logs out with fetch (CSRF token in a header) and then goes where it is told.
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(200);
                    response.setContentType("application/json");
                    response.setHeader("Cache-Control", "no-store");
                    writeQuietly(response, "{\"redirectUrl\":\"/\"}");
                }))
                .build();
    }

    /** The session cookie: HttpOnly, Secure, SameSite=Lax, host-only and for the whole site. */
    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    private static void writeQuietly(jakarta.servlet.http.HttpServletResponse response, String body) {
        try {
            response.getWriter().write(body);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

package dev.healthintelligence.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import dev.healthintelligence.core.shared.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /internal/**} for ai-service where there is no identity provider to issue service tokens
 * (the Render profile): a shared secret of at least 32 characters in {@code hip.internal.token},
 * sent as a bearer token and compared in constant time. Without the property the paths fall to the
 * main chain, where only a valid JWT gets through, and no internal endpoint exists.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${hip.internal.token:}'.isEmpty()")
class InternalSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, CorrelationIds correlationIds,
                                                    @Value("${hip.internal.token}") String token) throws Exception {
        if (token.length() < 32) {
            throw new IllegalStateException("hip.internal.token must have at least 32 characters");
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        String header = request.getHeader("Authorization");
                        if (header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                    "ai-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(SecurityConfiguration.problemEntryPoint(correlationIds))
                        .accessDeniedHandler(SecurityConfiguration.problemAccessDeniedHandler(correlationIds)))
                .build();
    }
}

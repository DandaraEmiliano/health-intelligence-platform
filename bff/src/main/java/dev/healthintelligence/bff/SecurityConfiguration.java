package dev.healthintelligence.bff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import java.net.URI;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String REGISTRATION = "keycloak";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations,
                                            @Value("${hip.oidc.end-session-uri}") String endSessionUri) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/bff/user").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login.defaultSuccessUrl("/", true)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(withAccountCreation(registrations))))
                // An XHR without a session gets 401, never a redirect to the login page.
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                // Double-submit cookie for the SPA: XSRF-TOKEN readable by JavaScript, echoed in
                // X-XSRF-TOKEN on unsafe methods. The session cookie itself is HttpOnly.
                .csrf(csrf -> csrf.spa())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(keycloakLogout(endSessionUri)))
                .headers(headers -> headers
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .oauth2Client(Customizer.withDefaults())
                .build();
    }

    /** "Criar conta" sends {@code ?prompt=create}, which Keycloak answers with its registration page. */
    static OAuth2AuthorizationRequestResolver withAccountCreation(ClientRegistrationRepository registrations) {
        var defaults = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        defaults.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withPrompt(request, defaults.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return withPrompt(request, defaults.resolve(request, registrationId));
            }

            private OAuth2AuthorizationRequest withPrompt(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
                if (resolved == null || !"create".equals(request.getParameter("prompt"))) {
                    return resolved;
                }
                return OAuth2AuthorizationRequest.from(resolved)
                        .additionalParameters(params -> params.put("prompt", "create")).build();
            }
        };
    }

    /**
     * RP-initiated logout: after the local session is gone, the browser goes to Keycloak's end-session
     * endpoint with the ID token as hint, and comes back to the app's home page.
     */
    static LogoutSuccessHandler keycloakLogout(String endSessionUri) {
        return (request, response, authentication) -> {
            String home = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                    .replacePath("/").replaceQuery(null).build().toUriString();
            UriComponentsBuilder target = UriComponentsBuilder.fromUriString(endSessionUri)
                    .queryParam("client_id", "hip-bff")
                    .queryParam("post_logout_redirect_uri", home);
            if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
                target.queryParam("id_token_hint", user.getIdToken().getTokenValue());
            }
            String url = target.encode().build().toUriString();
            String accept = request.getHeader("Accept");
            if (accept != null && accept.contains("application/json")) {
                // The SPA logs out with fetch (so the CSRF token travels in a header) and then
                // navigates to Keycloak itself.
                response.setStatus(200);
                response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"redirectUrl\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
                return;
            }
            response.sendRedirect(url);
        };
    }

    /**
     * The Keycloak client, built from configuration without a discovery call at start-up. The
     * browser is sent to the public authorization endpoint; token, keys and user info are fetched
     * from the internal address. The issuer is still checked on every ID token.
     */
    @Bean
    ClientRegistrationRepository clientRegistrations(@Value("${hip.oidc.issuer}") String issuer,
                                                     @Value("${hip.oidc.public-base}") String publicBase,
                                                     @Value("${hip.oidc.internal-base}") String internalBase,
                                                     @Value("${hip.oidc.client-secret}") String clientSecret) {
        String realmPath = URI.create(issuer).getPath() + "/protocol/openid-connect";
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId(REGISTRATION)
                .clientId("hip-bff")
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(issuer)
                .authorizationUri(publicBase + realmPath + "/auth")
                .tokenUri(internalBase + realmPath + "/token")
                .jwkSetUri(internalBase + realmPath + "/certs")
                .userInfoUri(internalBase + realmPath + "/userinfo")
                .userNameAttributeName("sub")
                .build());
    }

    /**
     * The only thing the browser holds: an opaque session id. {@code __Host-} forces Secure, Path=/
     * and no Domain; browsers accept Secure cookies on http://localhost. Lax rather than Strict, so
     * the cookie survives the redirect back from an identity provider on another site; unsafe
     * requests are still protected by the CSRF token.
     */
    @Bean
    CookieSerializer sessionCookie() {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName("__Host-hip-session");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(true);
        cookie.setSameSite("Lax");
        return cookie;
    }

    /** Tokens are kept in the (Redis) session, not in an in-memory map: any instance can relay. */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClients() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Hands out a valid access token, refreshing it with the refresh token when it has expired. */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientRepository authorizedClients) {
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode().refreshToken().build());
        return manager;
    }
}

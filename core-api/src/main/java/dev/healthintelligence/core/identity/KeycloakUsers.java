package dev.healthintelligence.core.identity;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Keycloak Admin REST API with a client-credentials token of {@code core-api-admin}, whose service
 * account holds only {@code manage-users} and {@code view-users} (realm-management).
 */
@Component
@ConditionalOnExpression("${hip.identity.admin.enabled:true} and '${hip.auth.mode:oidc}' != 'local'")
class KeycloakUsers implements IdentityProviderUsers {

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    KeycloakUsers(@Value("${hip.identity.admin.base-url}") String baseUrl,
                  @Value("${hip.identity.admin.realm}") String realm,
                  @Value("${hip.identity.admin.client-id}") String clientId,
                  @Value("${hip.identity.admin.client-secret}") String clientSecret) {
        // Built here: Spring Boot 4 only auto-configures RestClient.Builder with spring-boot-restclient.
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public void delete(String subject) {
        try {
            http.delete()
                    .uri("/admin/realms/{realm}/users/{id}", realm, subject)
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // Deleted by an earlier, interrupted run.
        }
    }

    private String accessToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> token = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(), (request, response) -> {
                    throw new IllegalStateException("core-api-admin credentials rejected by the identity provider");
                })
                .body(Map.class);
        if (token == null || !(token.get("access_token") instanceof String value)) {
            throw new IllegalStateException("No access token from the identity provider");
        }
        return value;
    }
}

package dev.healthintelligence.core.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** With the built-in sign-in, the deleted account's credential goes, so it cannot sign in again (T18). */
@Component
@ConditionalOnProperty(name = "hip.auth.mode", havingValue = "local")
class LocalIdentityProviderUsers implements IdentityProviderUsers {

    private final LocalCredentials credentials;

    LocalIdentityProviderUsers(LocalCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public void delete(String subject) {
        credentials.delete(subject);
    }
}

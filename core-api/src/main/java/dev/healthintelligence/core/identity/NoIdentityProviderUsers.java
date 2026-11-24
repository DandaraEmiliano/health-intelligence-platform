package dev.healthintelligence.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Used when the Keycloak admin client is disabled; says so loudly instead of pretending. */
@Component
@ConditionalOnExpression("!${hip.identity.admin.enabled:true} and '${hip.auth.mode:oidc}' != 'local'")
class NoIdentityProviderUsers implements IdentityProviderUsers {

    private static final Logger log = LoggerFactory.getLogger(NoIdentityProviderUsers.class);

    @Override
    public void delete(String subject) {
        log.warn("Identity provider admin client disabled: user {} was not deleted there", subject);
    }
}

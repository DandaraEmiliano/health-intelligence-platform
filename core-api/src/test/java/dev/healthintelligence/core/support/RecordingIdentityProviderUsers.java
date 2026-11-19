package dev.healthintelligence.core.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.healthintelligence.core.identity.IdentityProviderUsers;

/** Records deletions instead of calling Keycloak; can be told to fail once for one user. */
public class RecordingIdentityProviderUsers implements IdentityProviderUsers {

    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> failOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void delete(String subject) {
        if (failOnce.remove(subject)) {
            throw new IllegalStateException("identity provider unavailable");
        }
        deleted.add(subject);
    }

    public List<String> deleted() {
        return deleted;
    }

    /** Tests share one context, so a failure is targeted at the test's own user. */
    public void failOnceFor(String subject) {
        failOnce.add(subject);
    }
}

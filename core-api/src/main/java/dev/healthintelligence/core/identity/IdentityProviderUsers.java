package dev.healthintelligence.core.identity;

/** Removes the user at the identity provider, so the deleted account cannot sign in again (T18). */
public interface IdentityProviderUsers {

    /** Idempotent: a user that no longer exists counts as deleted. */
    void delete(String subject);
}

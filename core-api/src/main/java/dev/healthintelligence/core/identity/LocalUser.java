package dev.healthintelligence.core.identity;

import java.io.Serializable;

/**
 * Who is signed in with the built-in sign-in (hip.auth.mode=local): what a token would otherwise
 * carry. Kept in the server-side session; the browser only holds the session cookie.
 */
public record LocalUser(String subject, String email) implements Serializable {
}

package dev.healthintelligence.core.identity;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import dev.healthintelligence.core.shared.ProblemException;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the local account of the authenticated user from the access token (or, with the built-in
 * sign-in, from the session's {@link LocalUser}), creating it on the
 * first request (just-in-time provisioning). Call it from the web layer, before the use case's own
 * transaction starts.
 */
@Component
public class CurrentUser {

    private final UserAccounts accounts;

    CurrentUser(UserAccounts accounts) {
        this.accounts = accounts;
    }

    public UserId id() {
        UserAccount account = resolve();
        if (account.status() != AccountStatus.ACTIVE) {
            throw ProblemException.forbidden("ACCOUNT_NOT_ACTIVE");
        }
        return new UserId(account.id());
    }

    /** The account whatever its status, for the deletion request itself (repeating it is harmless). */
    UserAccount resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject;
        String email;
        Set<Role> roles;
        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            subject = jwt.getSubject();
            email = jwt.getClaimAsString("email");
            roles = knownRoles(jwt);
        } else if (authentication != null && authentication.getPrincipal() instanceof LocalUser user) {
            // Built-in sign-in (hip.auth.mode=local): every account is a patient.
            subject = user.subject();
            email = user.email();
            roles = EnumSet.of(Role.PATIENT);
        } else {
            throw new IllegalStateException("CurrentUser requires an authenticated request");
        }
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw ProblemException.forbidden("ACCOUNT_CLAIMS_MISSING");
        }

        UserAccount account = accounts.findAndSync(subject, email, roles).orElseGet(() -> {
            try {
                return accounts.create(subject, email, roles);
            } catch (DataIntegrityViolationException concurrentlyCreated) {
                return accounts.findAndSync(subject, email, roles).orElseThrow(() -> concurrentlyCreated);
            }
        });
        return account;
    }

    private static Set<Role> knownRoles(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList("roles");
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (claim != null) {
            for (Role role : Role.values()) {
                if (claim.contains(role.name())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}

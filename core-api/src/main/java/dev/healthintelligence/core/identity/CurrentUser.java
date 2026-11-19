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
 * Resolves the local account of the authenticated user from the access token, creating it on the
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
        Jwt jwt = currentJwt();
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw ProblemException.forbidden("ACCOUNT_CLAIMS_MISSING");
        }
        Set<Role> roles = knownRoles(jwt);

        UserAccount account = accounts.findAndSync(subject, email, roles).orElseGet(() -> {
            try {
                return accounts.create(subject, email, roles);
            } catch (DataIntegrityViolationException concurrentlyCreated) {
                return accounts.findAndSync(subject, email, roles).orElseThrow(() -> concurrentlyCreated);
            }
        });
        if (account.status() != AccountStatus.ACTIVE) {
            throw ProblemException.forbidden("ACCOUNT_NOT_ACTIVE");
        }
        return new UserId(account.id());
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new IllegalStateException("CurrentUser requires an authenticated JWT request");
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

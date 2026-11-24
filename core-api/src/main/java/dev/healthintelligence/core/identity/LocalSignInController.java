package dev.healthintelligence.core.identity;

import java.util.List;
import java.util.Map;

import dev.healthintelligence.core.audit.ActorType;
import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditOutcome;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.ProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

/**
 * The built-in sign-in (hip.auth.mode=local), with the same endpoints the SPA uses with the BFF:
 * {@code GET /bff/user}, plus {@code POST /bff/login} and {@code POST /bff/register}. The session
 * lives on the server (Spring Session, Redis); the browser holds only the HttpOnly cookie, and
 * unsafe requests need the CSRF token (ADR 0014). Every outcome is audited; passwords never are.
 */
@RestController
@ConditionalOnProperty(name = "hip.auth.mode", havingValue = "local")
class LocalSignInController {

    record Credentials(String email, String password) {
    }

    record SessionUser(String email, String name) {
    }

    private final LocalCredentials credentials;
    private final SignInThrottle throttle;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;
    private final SecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    LocalSignInController(LocalCredentials credentials, SignInThrottle throttle, AuditLog auditLog,
                          TransactionTemplate transaction) {
        this.credentials = credentials;
        this.throttle = throttle;
        this.auditLog = auditLog;
        this.transaction = transaction;
    }

    @GetMapping("/bff/user")
    ResponseEntity<SessionUser> user(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LocalUser user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionUser(user.email(), null));
    }

    @PostMapping("/bff/register")
    ResponseEntity<SessionUser> register(@RequestBody Credentials body, HttpServletRequest request, HttpServletResponse response) {
        LocalUser user = credentials.register(body.email(), body.password());
        audit("LOCAL_CREDENTIAL_CREATED", AuditOutcome.SUCCESS);
        signIn(user, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new SessionUser(user.email(), null));
    }

    @PostMapping("/bff/login")
    ResponseEntity<SessionUser> login(@RequestBody Credentials body, HttpServletRequest request, HttpServletResponse response) {
        String email = LocalCredentials.normalize(body.email());
        if (email == null) {
            throw invalid();
        }
        throttle.check(email);
        LocalUser user = credentials.authenticate(email, body.password()).orElse(null);
        if (user == null) {
            throttle.failed(email);
            audit("SIGN_IN", AuditOutcome.FAILURE);
            throw invalid();
        }
        throttle.succeeded(email);
        audit("SIGN_IN", AuditOutcome.SUCCESS);
        signIn(user, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionUser(user.email(), null));
    }

    /** A new session id on every sign-in, so a session fixed before it is worth nothing. */
    private void signIn(LocalUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.PATIENT.name()))));
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
    }

    private void audit(String action, AuditOutcome outcome) {
        transaction.executeWithoutResult(status -> auditLog.record(
                new AuditRecord(null, ActorType.SYSTEM, action, "USER_ACCOUNT", null, null, outcome, Map.of())));
    }

    private static ProblemException invalid() {
        return new ProblemException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail or password is incorrect");
    }
}

package dev.healthintelligence.core.identity;

import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.audit.ActorType;
import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditOutcome;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges one account, in an order that is safe to interrupt: data outside the database first
 * (listeners of {@link AccountPurgeRequested}), then the identity provider user, then the rows.
 * If a step fails the account keeps its DELETION_REQUESTED row and the next run starts over; every
 * step is idempotent. Deleting the account row cascades to everything the user owns (V1 foreign
 * keys). Audit events have no foreign keys and stay (retention is a legal decision).
 */
@Component
class AccountPurger {

    private final UserAccountRepository accounts;
    private final ApplicationEventPublisher events;
    private final IdentityProviderUsers identityProvider;
    private final AuditLog auditLog;

    AccountPurger(UserAccountRepository accounts, ApplicationEventPublisher events,
                  IdentityProviderUsers identityProvider, AuditLog auditLog) {
        this.accounts = accounts;
        this.events = events;
        this.identityProvider = identityProvider;
        this.auditLog = auditLog;
    }

    @Transactional
    public void purge(UUID accountId) {
        UserAccount account = accounts.findById(accountId).orElse(null);
        if (account == null || account.status() != AccountStatus.DELETION_REQUESTED) {
            return;
        }
        events.publishEvent(new AccountPurgeRequested(new UserId(account.id())));
        identityProvider.delete(account.idpSubject());
        accounts.delete(account);
        auditLog.record(new AuditRecord(null, ActorType.SYSTEM, "ACCOUNT_PURGED", "USER_ACCOUNT", account.id(), null,
                AuditOutcome.SUCCESS, Map.of()));
    }
}

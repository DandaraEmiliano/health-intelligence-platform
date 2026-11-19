package dev.healthintelligence.core.identity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.Ids;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional steps of just-in-time provisioning, kept apart so each runs in its own transaction. */
@Component
class UserAccounts {

    private final UserAccountRepository accounts;
    private final AuditLog auditLog;

    UserAccounts(UserAccountRepository accounts, AuditLog auditLog) {
        this.accounts = accounts;
        this.auditLog = auditLog;
    }

    /** Finds the account for the subject and syncs it, or returns empty if it does not exist yet. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<UserAccount> findAndSync(String subject, String email, Set<Role> roles) {
        return accounts.findByIdpSubject(subject).map(account -> {
            if (account.syncWith(email, roles)) {
                accounts.saveAndFlush(account);
            }
            return account;
        });
    }

    /** Creates the account. Fails with a unique violation if a concurrent request created it first. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UserAccount create(String subject, String email, Set<Role> roles) {
        UserAccount account = accounts.saveAndFlush(new UserAccount(Ids.newId(), subject, email, roles));
        auditLog.record(AuditRecord.userSuccess(account.id(), "USER_ACCOUNT_PROVISIONED", "USER_ACCOUNT",
                account.id(), null, Map.of("roles", String.join(",", roles.stream().map(Enum::name).sorted().toList()))));
        return account;
    }
}

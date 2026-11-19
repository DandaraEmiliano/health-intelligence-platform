package dev.healthintelligence.core.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService {

    private final UserAccountRepository accounts;
    private final AuditLog auditLog;
    private final Clock clock;

    AccountService(UserAccountRepository accounts, AuditLog auditLog, Clock clock) {
        this.accounts = accounts;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Blocks the account at once. Asking again changes nothing and is not an error. */
    @Transactional
    void requestDeletion(java.util.UUID accountId) {
        UserAccount account = accounts.findById(accountId).orElseThrow();
        if (account.requestDeletion(Instant.now(clock).truncatedTo(ChronoUnit.MICROS))) {
            accounts.saveAndFlush(account);
            auditLog.record(AuditRecord.userSuccess(account.id(), "ACCOUNT_DELETION_REQUESTED", "USER_ACCOUNT",
                    account.id(), null, Map.of()));
        }
    }
}

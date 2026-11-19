package dev.healthintelligence.core.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds accounts whose deletion was requested at least {@code hip.accounts.purge.after} ago and
 * purges them one at a time. The delay must exceed the access token lifetime (5 minutes): a token
 * still valid after the purge would otherwise provision a new, empty account.
 */
@Component
public class AccountPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeJob.class);

    private final UserAccountRepository accounts;
    private final AccountPurger purger;
    private final Clock clock;
    private final Duration after;
    private final int batchSize;

    AccountPurgeJob(UserAccountRepository accounts, AccountPurger purger, Clock clock,
                    @Value("${hip.accounts.purge.after}") Duration after,
                    @Value("${hip.accounts.purge.batch-size}") int batchSize) {
        this.accounts = accounts;
        this.purger = purger;
        this.clock = clock;
        this.after = after;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${hip.accounts.purge.interval}", initialDelayString = "${hip.accounts.purge.interval}")
    void scheduled() {
        purgeDue();
    }

    /** Returns how many accounts were purged in this run. */
    public int purgeDue() {
        List<UUID> due = accounts.findDueForPurge(Instant.now(clock).minus(after), Limit.of(batchSize));
        int purged = 0;
        for (UUID id : due) {
            try {
                purger.purge(id);
                purged++;
            } catch (RuntimeException e) {
                log.warn("Account {} not purged, will retry: {}", id, e.getClass().getSimpleName());
            }
        }
        return purged;
    }
}

package dev.healthintelligence.core.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link OutboxPublisher} on a fixed delay. Disable with {@code hip.outbox.relay.enabled=false}. */
@Component
@ConditionalOnProperty(name = "hip.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPublisher publisher;

    OutboxRelay(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${hip.outbox.relay.fixed-delay}", initialDelayString = "${hip.outbox.relay.fixed-delay}")
    void run() {
        try {
            publisher.publishPending();
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed: {}", e.getClass().getSimpleName());
        }
    }
}

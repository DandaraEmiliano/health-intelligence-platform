package dev.healthintelligence.core.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TreeMap;

import dev.healthintelligence.core.shared.CorrelationIds;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.RequestOrigin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records sensitive operations. Runs in the caller's transaction, so an audit row exists exactly
 * when the audited change was committed.
 */
@Service
public class AuditLog {

    private final AuditEventRepository events;
    private final CorrelationIds correlationIds;
    private final RequestOrigin requestOrigin;
    private final JsonMapper json;
    private final Clock clock;

    AuditLog(AuditEventRepository events, CorrelationIds correlationIds, RequestOrigin requestOrigin,
             JsonMapper json, Clock clock) {
        this.events = events;
        this.correlationIds = correlationIds;
        this.requestOrigin = requestOrigin;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditRecord record) {
        HttpServletRequest request = requestOrigin.currentRequest();
        events.save(new AuditEvent(
                Ids.newId(),
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                record,
                correlationIds.current(),
                request == null ? null : requestOrigin.ipAddress(request),
                request == null ? null : requestOrigin.userAgent(request),
                json.writeValueAsString(new TreeMap<>(record.metadata()))));
    }
}

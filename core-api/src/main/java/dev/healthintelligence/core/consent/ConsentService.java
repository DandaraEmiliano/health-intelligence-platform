package dev.healthintelligence.core.consent;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.RequestOrigin;
import dev.healthintelligence.core.shared.UserId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConsentService {

    private final ConsentRecordRepository records;
    private final AuditLog auditLog;
    private final RequestOrigin requestOrigin;
    private final Clock clock;

    ConsentService(ConsentRecordRepository records, AuditLog auditLog, RequestOrigin requestOrigin, Clock clock) {
        this.records = records;
        this.auditLog = auditLog;
        this.requestOrigin = requestOrigin;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<ConsentRecord> current(UserId user) {
        return records.findCurrent(user.value()).stream()
                .sorted(Comparator.comparing(ConsentRecord::consentType))
                .toList();
    }

    @Transactional
    ConsentRecord record(UserId user, ConsentType type, boolean granted, String documentVersion) {
        HttpServletRequest request = requestOrigin.currentRequest();
        ConsentRecord record = records.save(new ConsentRecord(
                Ids.newId(), user.value(), type, documentVersion, granted,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                request == null ? null : requestOrigin.ipAddress(request),
                request == null ? null : requestOrigin.userAgent(request)));
        auditLog.record(AuditRecord.userSuccess(user.value(), granted ? "CONSENT_GRANTED" : "CONSENT_REVOKED",
                "CONSENT_RECORD", record.id(), null,
                Map.of("type", type.name(), "documentVersion", documentVersion)));
        // Phase 5: revoking AI_PROCESSING publishes an event that stops extraction and purges embeddings.
        return record;
    }
}

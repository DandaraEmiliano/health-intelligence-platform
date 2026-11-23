package dev.healthintelligence.core.consent;

import java.time.Instant;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every consent decision ever recorded, including revocations. */
@Component
class ConsentDataExport implements PersonalDataExport {

    record Decision(String type, boolean granted, String documentVersion, Instant recordedAt) {
    }

    private final ConsentRecordRepository records;

    ConsentDataExport(ConsentRecordRepository records) {
        this.records = records;
    }

    @Override
    public String section() {
        return "consents";
    }

    @Override
    @Transactional(readOnly = true)
    public Object export(UserId user, PatientId patient) {
        return records.findByUserIdOrderByRecordedAtAscIdAsc(user.value()).stream()
                .map(r -> new Decision(r.consentType().name(), r.granted(), r.documentVersion(), r.recordedAt()))
                .toList();
    }
}

package dev.healthintelligence.core.healthrecords;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.ETags;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.PatientScope;
import dev.healthintelligence.core.shared.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Create, read, replace and delete for any record kind, always scoped to one patient. */
@Service
class HealthRecordService {

    /** Per patient and kind; keeps lists unpaginated and bounds abuse. */
    static final int MAX_RECORDS_PER_KIND = 200;

    private final AuditLog auditLog;
    private final Clock clock;

    HealthRecordService(AuditLog auditLog, Clock clock) {
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    <T extends HealthRecord<R>, R> List<T> list(HealthRecordKind<T, R> kind, PatientScope scope) {
        return kind.repository().findByPatientIdOrderByCreatedAtAscIdAsc(scope.patient());
    }

    @Transactional(readOnly = true)
    <T extends HealthRecord<R>, R> T get(HealthRecordKind<T, R> kind, PatientScope scope, UUID id) {
        return kind.repository().findByIdAndPatientId(id, scope.patient())
                .orElseThrow(() -> ProblemException.notFound(kind.notFoundCode()));
    }

    @Transactional
    <T extends HealthRecord<R>, R> T create(HealthRecordKind<T, R> kind, PatientScope scope, R request) {
        validate(kind, request);
        if (kind.repository().countByPatientId(scope.patient()) >= MAX_RECORDS_PER_KIND) {
            throw ProblemException.conflict("RECORD_LIMIT_REACHED");
        }
        T record = kind.repository().saveAndFlush(kind.create(Ids.newId(), scope.patient(), request));
        audit(kind, scope, record, "CREATED");
        return record;
    }

    @Transactional
    <T extends HealthRecord<R>, R> T replace(HealthRecordKind<T, R> kind, PatientScope scope, UUID id,
                                             String ifMatch, R request) {
        T record = get(kind, scope, id);
        ETags.requireMatch(ifMatch, record.version());
        validate(kind, request);
        record.replaceWith(request);
        record = kind.repository().saveAndFlush(record);
        audit(kind, scope, record, "UPDATED");
        return record;
    }

    @Transactional
    <T extends HealthRecord<R>, R> void delete(HealthRecordKind<T, R> kind, PatientScope scope, UUID id) {
        T record = get(kind, scope, id);
        kind.repository().delete(record);
        audit(kind, scope, record, "DELETED");
    }

    private <T extends HealthRecord<R>, R> void validate(HealthRecordKind<T, R> kind, R request) {
        var errors = kind.validate(request, LocalDate.now(clock));
        if (!errors.isEmpty()) {
            throw ProblemException.validation(errors);
        }
    }

    private void audit(HealthRecordKind<?, ?> kind, PatientScope scope, HealthRecord<?> record, String verb) {
        auditLog.record(AuditRecord.userSuccess(scope.user().value(), kind.resourceType() + "_" + verb,
                kind.resourceType(), record.id(), scope.patient().value(), Map.of()));
    }
}
